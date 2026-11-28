package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import bot.lazymatrix.GridScrollState
import bot.lazymatrix.LazyMatrixX
import bot.lazymatrix.LazyMatrixXY
import bot.lazymatrix.LazyMatrixY
import bot.lazymatrix.rememberGridScrollState
import kotlinx.coroutines.flow.first
import kotlin.math.roundToLong

internal object LazyMatrixSheetGrid : SheetGridRenderer {
    @Composable
    override fun Grid(spec: SheetGridSpec, modifier: Modifier) {
        BoxWithConstraints(modifier.background(LocalReaderColors.current.page)) {
            val leftLimit = (maxWidth - 48.dp).coerceAtLeast(spec.labelWidth)
            val topLimit = (maxHeight - 48.dp).coerceAtLeast(spec.labelHeight)
            var left = spec.labelWidth
            var pinnedColumns = 0
            while (pinnedColumns < minOf(spec.frozenColumns, spec.columnCount) && left + spec.columnWidth <= leftLimit) {
                left += spec.columnWidth
                pinnedColumns++
            }
            var top = spec.labelHeight
            var pinnedRows = 0
            while (pinnedRows < minOf(spec.frozenRows, spec.rowCount) && top + spec.height(pinnedRows) <= topLimit) {
                top += spec.height(pinnedRows)
                pinnedRows++
            }
            // Any frozen cells that cannot fit on this device remain reachable in the scrolling body.
            GridContent(
                spec.copy(frozenRows = pinnedRows, frozenColumns = pinnedColumns),
                leftWidth = left.coerceAtMost(maxWidth),
                topHeight = top.coerceAtMost(maxHeight),
                originalFrozenColumns = spec.frozenColumns,
            )
        }
    }

    @Composable
    private fun GridContent(spec: SheetGridSpec, leftWidth: Dp, topHeight: Dp, originalFrozenColumns: Int) {
        val rows = spec.rowCount - spec.frozenRows
        val columns = spec.columnCount - spec.frozenColumns
        val heights = remember(spec.rowHeights, spec.frozenRows) {
            spec.rowHeights.filterKeys { it >= spec.frozenRows }.mapKeys { (row, _) ->
                (row - spec.frozenRows).toLong()
            }
        }
        val state = rememberGridScrollState(
            rowCount = rows.coerceAtLeast(1).toLong(),
            columnCount = columns.coerceAtLeast(1).toLong(),
            rowHeight = spec.rowHeight,
            columnWidth = spec.columnWidth,
            rowHeightOverrides = heights,
        )
        val progress = LocalViewerProgress.current
        val field = "${spec.progressField}:matrix-dp"
        val density = LocalDensity.current
        val originX = with(density) { spec.frozenColumns.toLong() * spec.columnWidth.roundToPx() }
        val originY = with(density) {
            val base = spec.rowHeight.roundToPx()
            spec.frozenRows.toLong() * base + spec.rowHeights.entries.sumOf { (row, height) ->
                if (row < spec.frozenRows) (height.roundToPx() - base).toLong() else 0L
            }
        }
        val viewport = remember(state, originX, originY, density.density) {
            SheetViewport(state, originX, originY, density.density)
        }
        var restored by remember(progress, field) { mutableStateOf<SheetViewport?>(null) }
        LaunchedEffect(viewport, progress, field) {
            if (rows == 0 && columns == 0) return@LaunchedEffect
            snapshotFlow { state.isReady }.first { it }
            val saved = progress?.read(field)?.split(':').orEmpty()
            val previous = restored
            val savedX = previous?.x ?: saved.getOrNull(0)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val savedY = previous?.y ?: saved.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() }

            // Migrate offsets saved relative to the body in earlier versions.
            val old = progress?.read("${spec.progressField}:matrix")?.split(':').orEmpty()
            val oldColumn = progress?.number("${spec.progressField}:column")?.coerceIn(0, columns) ?: 0
            val oldRow = progress?.read("${spec.progressField}:rows")?.split(':').orEmpty()
            val oldRowIndex = (oldRow.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, rows)
            val x = savedX?.let { viewport.relativeX(it) } ?: old.getOrNull(0)?.toLongOrNull() ?: with(density) {
                oldColumn.toLong() * spec.columnWidth.roundToPx() +
                    (progress?.number("${spec.progressField}:x:${originalFrozenColumns + oldColumn}") ?: 0)
            }
            val y = savedY?.let { viewport.relativeY(it) } ?: old.getOrNull(1)?.toLongOrNull() ?: with(density) {
                val base = spec.rowHeight.roundToPx()
                val extra = heights.entries.sumOf { (row, height) ->
                    if (row < oldRowIndex) (height.roundToPx() - base).toLong() else 0L
                }
                oldRowIndex.toLong() * base + extra + (oldRow.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            state.scrollTo(if (columns > 0) x else 0L, if (rows > 0) y else 0L)
            restored = viewport
        }
        SaveViewerProgress(field) {
            val current = restored
            if (current != null) "${current.x}:${current.y}" else null
        }
        var bodyHeight by remember(state) { mutableStateOf(0) }
        val onScroller by rememberUpdatedState(spec.onScroller)
        DisposableEffect(state, rows) {
            if (rows > 0) onScroller(object : SheetGridScroller {
                override val viewportHeight: Int get() = bodyHeight
                override suspend fun scrollBy(dy: Float) {
                    state.animateScrollTo(state.scrollX, state.scrollY + dy.roundToLong())
                }
                override suspend fun scrollToTop() = state.scrollTo(state.scrollX, 0L)
                override suspend fun scrollToBottom() = state.scrollTo(state.scrollX, Long.MAX_VALUE)
            })
            onDispose { onScroller(null) }
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(topHeight).clipToBounds()) {
                Column(Modifier.width(leftWidth).clipToBounds()) {
                    Row {
                        spec.corner()
                        repeat(spec.frozenColumns) { spec.columnLabel(it) }
                    }
                    repeat(spec.frozenRows) { row ->
                        Row {
                            spec.rowLabel(row)
                            repeat(spec.frozenColumns) { column -> spec.cell(row, column) }
                        }
                    }
                }
                if (columns > 0) {
                    LazyMatrixX(
                        state,
                        Modifier.weight(1f).fillMaxHeight(),
                        prefetchPadding = 64.dp,
                    ) { row, column ->
                        if (row == 0L) {
                            val index = spec.frozenColumns + column.toInt()
                            Column(Modifier.matrixPaneContent(height = with(density) { topHeight.roundToPx() })) {
                                spec.columnLabel(index)
                                repeat(spec.frozenRows) { spec.cell(it, index) }
                            }
                        }
                    }
                }
            }
            if (rows > 0) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyMatrixY(
                        state,
                        Modifier.width(leftWidth).fillMaxHeight(),
                        prefetchPadding = 64.dp,
                        onLayoutInfo = { bodyHeight = it.viewportSize.height },
                    ) { row, column ->
                        if (column == 0L) {
                            val index = spec.frozenRows + row.toInt()
                            Row(Modifier.matrixPaneContent(width = with(density) { leftWidth.roundToPx() })) {
                                spec.rowLabel(index)
                                repeat(spec.frozenColumns) { spec.cell(index, it) }
                            }
                        }
                    }
                    if (columns > 0) {
                        LazyMatrixXY(state, Modifier.weight(1f).fillMaxHeight()) { row, column ->
                            spec.cell(spec.frozenRows + row.toInt(), spec.frozenColumns + column.toInt())
                        }
                    }
                }
            }
        }
    }

    private fun SheetGridSpec.height(row: Int) = rowHeights[row] ?: rowHeight
}

/** Retains a sheet-relative anchor when a freeze toggle moves the origin of the scrolling panes. */
private class SheetViewport(
    private val state: GridScrollState,
    private val originX: Long,
    private val originY: Long,
    density: Float,
) {
    private val pixelsPerDp = density.toDouble()

    val x: Double get() = absolute(state.scrollX, originX)
    val y: Double get() = absolute(state.scrollY, originY)

    fun relativeX(value: Double) = relative(value, originX)
    fun relativeY(value: Double) = relative(value, originY)

    // Keep the start pinned to the start, even when there are more or fewer frozen cells.
    private fun absolute(offset: Long, origin: Long): Double =
        if (offset == 0L) 0.0 else (origin + offset) / pixelsPerDp

    private fun relative(value: Double, origin: Long): Long =
        ((value * pixelsPerDp).roundToLong() - origin).coerceAtLeast(0L)
}

// The cell owns a regular lazy slot while its pinned content fills the pane's other axis.
internal fun Modifier.matrixPaneContent(width: Int? = null, height: Int? = null): Modifier = layout { measurable, constraints ->
    val content = measurable.measure(Constraints.fixed(width ?: constraints.maxWidth, height ?: constraints.maxHeight))
    layout(constraints.maxWidth, constraints.maxHeight) { content.placeRelative(0, 0) }
}
