package com.xfiles.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import com.xfiles.fily.ReaderPosition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.structure.CellLook
import com.xfiles.documents.structure.ContentBlock

/** Row height of a sheet row: wrapped text gets a taller row. Matches the built-in grid. */
internal fun sheetRowHeight(row: ContentBlock.TableRow, palette: Map<Int, CellLook>): Int =
    if (row.styles.any { palette[it]?.wrap == true }) WRAPPED_ROW_HEIGHT else ROW_HEIGHT

/**
 * The sheet drawn by a supplied [SheetGridRenderer]: every column at once, with frozen rows and columns
 * pinned and the letters and row numbers kept in step by the renderer.
 */
@Composable
internal fun ColumnScope.SuppliedSheetGrid(
    renderer: SheetGridRenderer,
    sheet: SheetPreview,
    palette: Map<Int, CellLook>,
    frozenRows: Int,
    frozenColumns: Int,
    cellEditor: CellEditor?,
    onEdited: () -> Unit,
    currentSheet: () -> String,
    turnSheet: (Int) -> String?,
    hasMultipleSheets: Boolean,
) {
    val haptics = rememberHaptics()
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var scroller by remember { mutableStateOf<SheetGridScroller?>(null) }
    val registerScroll = LocalViewerScroll.current
    val currentTitle by rememberUpdatedState(currentSheet)
    val turn by rememberUpdatedState(turnSheet)
    DisposableEffect(scroller, registerScroll, hasMultipleSheets) {
        val current = scroller
        val unregister = if (current != null) {
            registerScroll(SheetGridViewerScroll(
                scroller = current,
                currentPosition = {
                    val title = currentTitle()
                    val cell = selected?.takeIf { title == sheet.title }?.let { (row, column) ->
                        "${columnName(column)}${sheet.rows[row].row ?: row + 1}"
                    }
                    ReaderPosition(sheet = title, cell = cell)
                },
                turnSheet = { turn(it) },
                hasMultipleSheets = hasMultipleSheets,
            ))
        } else ({})
        onDispose { unregister() }
    }
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceIn(1f, 2.5f)
    val rowHeight = (ROW_HEIGHT * fontScale).roundToInt()
    val labelWidth = (LABEL_WIDTH * fontScale).roundToInt()
    val columnWidth = (CELL_WIDTH * fontScale.coerceAtMost(1.65f)).roundToInt()
    val heights = remember(sheet, palette, fontScale) {
        buildMap {
            sheet.rows.forEachIndexed { index, row ->
                val height = (sheetRowHeight(row, palette) * fontScale).roundToInt()
                if (height != rowHeight) put(index, height.dp)
            }
        }
    }
    val mergeIndex = remember(sheet) { SheetMergeIndex(sheet) }
    val rowOffsets = remember(sheet, heights, density) {
        if (sheet.merges.isEmpty()) IntArray(0) else with(density) {
            IntArray(sheet.rows.size + 1).also { offsets ->
                for (index in sheet.rows.indices) {
                    offsets[index + 1] = offsets[index] + (heights[index] ?: rowHeight.dp).roundToPx()
                }
            }
        }
    }
    val columnPixels = with(density) { columnWidth.dp.roundToPx() }
    val spec = remember(sheet, palette, frozenRows, frozenColumns, heights, mergeIndex, rowOffsets, columnPixels, fontScale, cellEditor) {
        SheetGridSpec(
            progressField = "sheet:${sheet.title}",
            rowCount = sheet.rows.size,
            columnCount = sheet.columnCount(),
            frozenRows = frozenRows,
            frozenColumns = frozenColumns,
            columnWidth = columnWidth.dp,
            rowHeight = rowHeight.dp,
            rowHeights = heights,
            labelWidth = labelWidth.dp,
            labelHeight = rowHeight.dp,
            corner = { GridLabel("", labelWidth, rowHeight) },
            columnLabel = { column -> GridLabel(columnName(column), columnWidth, rowHeight) },
            rowLabel = { index ->
                val row = sheet.rows[index]
                GridLabel((row.row ?: index + 1).toString(), labelWidth, (heights[index] ?: rowHeight.dp).value.toInt())
            },
            cell = { index, column ->
                val merge = mergeIndex[index, column]
                val anchorRow = merge?.firstRow ?: index
                val anchorColumn = merge?.firstColumn ?: column
                val row = sheet.rows[anchorRow]
                val number = row.row ?: anchorRow + 1
                val text = row.cells.getOrNull(anchorColumn).orEmpty()
                val look = row.styles.getOrNull(anchorColumn)?.let(palette::get)
                val spans = sheet.runs[number to anchorColumn].orEmpty()
                val address = anchorRow to anchorColumn
                val isSelected = selected == address
                val activate: () -> Unit = {
                    haptics.tick()
                    selected = address
                    if (cellEditor != null) editing = EditTarget(sheet.title, number, anchorColumn, text)
                }
                if (merge == null) {
                    SpreadsheetCell(
                        text, look, spans, row.header, (heights[anchorRow] ?: rowHeight.dp).value.toInt(),
                        selected = isSelected, editable = cellEditor != null, width = columnWidth, onClick = activate,
                    )
                } else {
                    Box(
                        Modifier.width(columnWidth.dp).height(heights[index] ?: rowHeight.dp).clipToBounds()
                            .semantics(mergeDescendants = true) { this.selected = isSelected }
                            .clickable(
                                onClickLabel = if (cellEditor != null) "Edit merged cell" else "Select merged cell",
                                onClick = activate,
                            ),
                        propagateMinConstraints = true,
                    ) {
                        SpreadsheetCell(
                            text, look, spans, row.header, (heights[anchorRow] ?: rowHeight.dp).value.toInt(),
                            selected = isSelected, editable = cellEditor != null, width = columnWidth,
                            // Each slot draws the part of the merged cell inside its viewport, including across frozen panes.
                            modifier = Modifier.mergeFragment(
                                width = (merge.lastColumn - anchorColumn + 1) * columnPixels,
                                height = rowOffsets[merge.lastRow + 1] - rowOffsets[anchorRow],
                                x = (column - anchorColumn) * columnPixels,
                                y = rowOffsets[index] - rowOffsets[anchorRow],
                            ).then(if (index == anchorRow && column == anchorColumn) Modifier else Modifier.clearAndSetSemantics {}),
                        )
                    }
                }
            },
            onScroller = { scroller = it },
        )
    }
    renderer.Grid(spec, Modifier.weight(1f).fillMaxWidth())

    editing?.let { target ->
        if (cellEditor == null) {
            editing = null
        } else {
            CellEditDialog(target, cellEditor, onDismiss = { editing = null }, onSaved = { editing = null; onEdited() })
        }
    }
}

private const val ROW_HEIGHT = 34
private const val WRAPPED_ROW_HEIGHT = 58


private data class SheetMerge(val firstRow: Int, val lastRow: Int, val firstColumn: Int, val lastColumn: Int)

private class SheetMergeIndex(sheet: SheetPreview) {
    private val merges = if (sheet.merges.isEmpty()) {
        emptyList()
    } else {
        val numbers = sheet.rows.mapIndexed { index, row -> row.row ?: index + 1 }
        sheet.merges.mapNotNull { merge ->
            val first = numbers.binarySearch(merge.firstRow)
            val end = numbers.binarySearch(merge.lastRow)
            val last = if (end >= 0) end else -end - 2
            val lastColumn = merge.lastColumn.coerceAtMost(sheet.columnCount() - 1)
            if (first < 0 || last < first || merge.firstColumn !in 0..lastColumn) null
            else SheetMerge(first, last, merge.firstColumn, lastColumn)
        }.sortedBy(SheetMerge::firstRow)
    }
    private val latestRow = IntArray(merges.size).apply {
        var last = -1
        merges.forEachIndexed { index, merge ->
            last = maxOf(last, merge.lastRow)
            this[index] = last
        }
    }

    operator fun get(row: Int, column: Int): SheetMerge? {
        var low = 0
        var high = merges.size
        while (low < high) {
            val middle = (low + high) / 2
            if (merges[middle].firstRow <= row) low = middle + 1 else high = middle
        }
        for (index in low - 1 downTo 0) {
            if (latestRow[index] < row) break
            val merge = merges[index]
            if (row <= merge.lastRow && column in merge.firstColumn..merge.lastColumn) return merge
        }
        return null
    }
}

private fun Modifier.mergeFragment(width: Int, height: Int, x: Int, y: Int): Modifier = layout { measurable, constraints ->
    val merged = measurable.measure(Constraints.fitPrioritizingWidth(width, width, height, height))
    layout(constraints.maxWidth, constraints.maxHeight) { merged.place(-x, -y) }
}
