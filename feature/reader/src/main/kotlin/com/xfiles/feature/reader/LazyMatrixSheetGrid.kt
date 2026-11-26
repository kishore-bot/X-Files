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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lazymatrix.LazyMatrixX
import dev.lazymatrix.LazyMatrixXY
import dev.lazymatrix.LazyMatrixY
import dev.lazymatrix.rememberGridScrollState
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
            while (pinnedColumns < spec.frozenColumns && left + spec.columnWidth <= leftLimit) {
                left += spec.columnWidth
                pinnedColumns++
            }
            var top = spec.labelHeight
            var pinnedRows = 0
            while (pinnedRows < spec.frozenRows && top + spec.height(pinnedRows) <= topLimit) {
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
        val field = "${spec.progressField}:matrix"
        val density = LocalDensity.current
        var restored by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(state, progress, field) {
            if (restored || (rows == 0 && columns == 0)) return@LaunchedEffect
            snapshotFlow { state.isReady }.first { it }
            val saved = progress?.read(field)?.split(':').orEmpty()
            val oldColumn = progress?.number("${spec.progressField}:column")?.coerceAtLeast(0) ?: 0
            val oldRow = progress?.read("${spec.progressField}:rows")?.split(':').orEmpty()
            val oldRowIndex = (oldRow.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, rows)
            val x = saved.getOrNull(0)?.toLongOrNull() ?: with(density) {
                oldColumn.toLong() * spec.columnWidth.roundToPx() +
                    (progress?.number("${spec.progressField}:x:${originalFrozenColumns + oldColumn}") ?: 0)
            }
            val y = saved.getOrNull(1)?.toLongOrNull() ?: with(density) {
                val defaultHeight = spec.rowHeight.roundToPx()
                val extra = heights.entries.sumOf { (row, height) ->
                    if (row < oldRowIndex) (height.roundToPx() - defaultHeight).toLong() else 0L
                }
                oldRowIndex.toLong() * defaultHeight + extra + (oldRow.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            state.scrollTo(if (columns > 0) x else 0L, if (rows > 0) y else 0L)
            restored = true
        }
        SaveViewerProgress(field) {
            if (restored && state.isReady) "${state.scrollX}:${state.scrollY}" else null
        }
        val onScroller by rememberUpdatedState(spec.onScroller)
        DisposableEffect(state, rows) {
            if (rows > 0) onScroller(object : SheetGridScroller {
                override val viewportHeight: Int get() = state.layoutInfo?.viewportSize?.height ?: 0
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
                    LazyMatrixX(state, Modifier.weight(1f).fillMaxHeight()) { row, column ->
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
                    LazyMatrixY(state, Modifier.width(leftWidth).fillMaxHeight()) { row, column ->
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

// The cell owns a regular lazy slot while its pinned content fills the pane's other axis.
internal fun Modifier.matrixPaneContent(width: Int? = null, height: Int? = null): Modifier = layout { measurable, constraints ->
    val content = measurable.measure(Constraints.fixed(width ?: constraints.maxWidth, height ?: constraints.maxHeight))
    layout(constraints.maxWidth, constraints.maxHeight) { content.placeRelative(0, 0) }
}
