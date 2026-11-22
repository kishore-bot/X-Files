package com.xfiles.feature.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
) {
    val haptics = rememberHaptics()
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var scroller by remember { mutableStateOf<SheetGridScroller?>(null) }
    val registerScroll = LocalViewerScroll.current
    DisposableEffect(scroller, registerScroll) {
        val current = scroller
        val unregister = if (current != null) registerScroll(SheetGridViewerScroll(current)) else ({})
        onDispose { unregister() }
    }
    val heights = remember(sheet, palette) {
        buildMap {
            sheet.rows.forEachIndexed { index, row ->
                val height = sheetRowHeight(row, palette)
                if (height != ROW_HEIGHT) put(index, height.dp)
            }
        }
    }
    val spec = remember(sheet, palette, frozenRows, frozenColumns, heights) {
        SheetGridSpec(
            rowCount = sheet.rows.size,
            columnCount = sheet.columnCount(),
            frozenRows = frozenRows,
            frozenColumns = frozenColumns,
            columnWidth = CELL_WIDTH.dp,
            rowHeight = ROW_HEIGHT.dp,
            rowHeights = heights,
            labelWidth = LABEL_WIDTH.dp,
            labelHeight = ROW_HEIGHT.dp,
            corner = { GridLabel("", LABEL_WIDTH) },
            columnLabel = { column -> GridLabel(columnName(column), CELL_WIDTH) },
            rowLabel = { index ->
                val row = sheet.rows[index]
                GridLabel((row.row ?: index + 1).toString(), LABEL_WIDTH, sheetRowHeight(row, palette))
            },
            cell = { index, column ->
                val row = sheet.rows[index]
                val number = row.row ?: index + 1
                val text = row.cells.getOrNull(column).orEmpty()
                val look = row.styles.getOrNull(column)?.let(palette::get)
                val spans = sheet.runs[number to column].orEmpty()
                SpreadsheetCell(text, look, spans, row.header, sheetRowHeight(row, palette)) {
                    haptics.tick()
                    editing = EditTarget(sheet.title, number, column, text)
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
