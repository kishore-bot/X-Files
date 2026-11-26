@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xfiles.feature.reader

import com.xfiles.design.rememberHaptics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.documents.structure.CellAlign
import com.xfiles.documents.structure.CellLook
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.StructuredDocument
import com.xfiles.documents.structure.TextSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Frozen rows and columns of one sheet, from the file's sheetView pane (read off the main thread by the
 * caller). The user's "Freeze first row/column" toggles add to these.
 */
data class SheetFreeze(val rows: Int = 0, val columns: Int = 0) {
    companion object {
        val None = SheetFreeze()
    }
}

/** A value to store in one cell. Rows and columns are 0-based. */
sealed interface CellValue {
    data class Text(val text: String) : CellValue

    data class Number(val value: Double) : CellValue
}

/** The stored value of a cell: [kind] is "Empty", "Text", "Number", "Bool", "Error" or "Formula". */
data class RawCell(val kind: String, val raw: String)

/**
 * XLSX cell editing through the engine. [read] fills the dialog with the stored value (not the formatted
 * one); [save] writes a new copy and returns once it has re-opened. Both run off the main thread and
 * throw on failure. Formula cells are read-only and never reach [save].
 */
interface CellEditor {
    suspend fun read(sheet: String, row: Int, column: Int): RawCell

    suspend fun save(sheet: String, row: Int, column: Int, value: CellValue)
}

@Composable
internal fun SpreadsheetPreview(
    document: StructuredDocument,
    freezeBySheet: Map<String, SheetFreeze> = emptyMap(),
    cellEditor: CellEditor? = null,
    onEdited: () -> Unit = {},
) {
    val palette = LocalReaderColors.current
    val workbook = remember(document.blocks) { toWorkbook(document.blocks) }
    val progress = LocalViewerProgress.current
    var selected by rememberSaveable { mutableIntStateOf(progress?.number("sheet") ?: 0) }
    var freezeRow by rememberSaveable { mutableStateOf(progress?.read("sheet-freeze-row") == "true") }
    var freezeColumn by rememberSaveable { mutableStateOf(progress?.read("sheet-freeze-column") == "true") }
    SaveViewerProgress("sheet") { selected.coerceIn(0, workbook.sheets.lastIndex.coerceAtLeast(0)).toString() }
    SaveViewerProgress("sheet-freeze-row") { freezeRow.toString() }
    SaveViewerProgress("sheet-freeze-column") { freezeColumn.toString() }
    val sheet = workbook.sheets.getOrNull(selected) ?: workbook.sheets.firstOrNull()
    if (sheet == null) {
        Text("This workbook has no readable rows.")
        return
    }
    val fromFile = freezeBySheet[sheet.title] ?: SheetFreeze.None
    val frozenRows = (if (freezeRow) maxOf(fromFile.rows, 1) else fromFile.rows).coerceIn(0, sheet.rows.size)
    val columns = sheet.columnCount()
    val frozenColumns = (if (freezeColumn) maxOf(fromFile.columns, 1) else fromFile.columns).coerceIn(0, columns)

    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (workbook.sheets.size > 1) {
            val index = workbook.sheets.indexOf(sheet).coerceAtLeast(0)
            ScrollableTabRow(selectedTabIndex = index, edgePadding = 0.dp) {
                workbook.sheets.forEachIndexed { tab, candidate ->
                    Tab(
                        selected = tab == index,
                        onClick = { haptics.segment(); selected = tab },
                        text = { Text(candidate.title, overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    )
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = freezeRow, onClick = { haptics.toggle(!freezeRow); freezeRow = !freezeRow }, label = { Text("Freeze first row") })
            FilterChip(selected = freezeColumn, onClick = { haptics.toggle(!freezeColumn); freezeColumn = !freezeColumn }, label = { Text("Freeze first column") })
        }
        if (document.truncated) Text("Showing the rows within the preview limit.", color = palette.secondary)
        key(sheet) {
            SuppliedSheetGrid(LazyMatrixSheetGrid, sheet, workbook.palette, frozenRows, frozenColumns, cellEditor, onEdited)
        }
    }
}

/** A cell chosen for editing. [row] is the 1-based sheet row shown in the grid; the editor takes 0-based. */
internal data class EditTarget(val sheet: String, val row: Int, val column: Int, val displayed: String)

@Composable
internal fun CellEditDialog(target: EditTarget, editor: CellEditor, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val reference = "${columnName(target.column)}${target.row}"
    var loading by remember(target) { mutableStateOf(true) }
    var kind by remember(target) { mutableStateOf("") }
    var text by remember(target) { mutableStateOf(target.displayed) }
    var asNumber by remember(target) { mutableStateOf(false) }
    var busy by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf<String?>(null) }

    LaunchedEffect(target) {
        try {
            val raw = editor.read(target.sheet, target.row - 1, target.column)
            kind = raw.kind
            text = raw.raw
            asNumber = raw.kind == "Number"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "The cell could not be read"
        } finally {
            loading = false
        }
    }

    fun save() {
        val number = text.trim().toDoubleOrNull()
        if (asNumber && number == null) {
            error = "Enter a number, or turn Number off to store text"
            return
        }
        val value = if (asNumber && number != null) CellValue.Number(number) else CellValue.Text(text)
        scope.launch {
            busy = true
            error = null
            try {
                editor.save(target.sheet, target.row - 1, target.column, value)
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "The cell could not be saved"
            } finally {
                busy = false
            }
        }
    }

    val readOnly = kind == "Formula"
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Cell $reference") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> CircularProgressIndicator()
                    readOnly -> Text("This cell holds a formula, so it is read-only here.")
                    else -> {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Value") },
                            enabled = !busy,
                        )
                        FilterChip(selected = asNumber, onClick = { asNumber = !asNumber }, label = { Text("Number") }, enabled = !busy)
                        Text("Saves a new copy. The original file is not changed.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (!readOnly) TextButton(onClick = { haptics.confirm(); save() }, enabled = !loading && !busy) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(if (readOnly) "Close" else "Cancel") } },
    )
}

@Composable
internal fun GridLabel(label: String, width: Int, height: Int = 34) {
    val palette = LocalReaderColors.current
    Box(
        Modifier.width(width.dp).height(height.dp)
            .background(palette.header)
            .border(0.5.dp, palette.grid),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = palette.secondary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun SpreadsheetCell(
    value: String,
    look: CellLook?,
    spans: List<TextSpan>,
    header: Boolean,
    height: Int,
    selected: Boolean,
    editable: Boolean,
    width: Int = CELL_WIDTH,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val align = when (look?.align) {
        CellAlign.Right -> TextAlign.End
        CellAlign.Center -> TextAlign.Center
        CellAlign.Left -> TextAlign.Start
        else -> if (value.toDoubleOrNull() != null) TextAlign.End else TextAlign.Start
    }
    val palette = LocalReaderColors.current
    val base = if (header) palette.header else palette.page
    val fill = look?.fill?.let { Color(it).compositeOver(base) } ?: base
    val foreground = readableInk(look?.color?.let(::Color) ?: palette.text, fill)
    val outline = if (selected) readableInk(palette.selection, fill, minimum = 3f) else palette.grid
    val underline = buildList {
        if (look?.underline == true) add(TextDecoration.Underline)
        if (look?.strikethrough == true) add(TextDecoration.LineThrough)
    }.takeIf { it.isNotEmpty() }?.let(TextDecoration::combine)
    val content = remember(value, spans, palette, fill) { richText(value, spans, palette = palette, background = fill) }
    Box(
        modifier.width(width.dp).height(height.dp)
            .background(fill)
            .border(
                if (selected) 2.dp else 0.5.dp,
                outline,
            )
            .then(
                if (onClick == null) Modifier else Modifier
                    .semantics { this.selected = selected }
                    .clickable(onClickLabel = if (editable) "Edit cell" else "Select cell", onClick = onClick),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            content,
            modifier = Modifier.fillMaxWidth(),
            color = foreground,
            fontWeight = if (header || look?.bold == true) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (look?.italic == true) FontStyle.Italic else FontStyle.Normal,
            fontSize = look?.size?.takeIf(Float::isFinite)?.coerceIn(8f, 22f)?.sp ?: 13.sp,
            textDecoration = underline,
            textAlign = align,
            maxLines = if (look?.wrap == true) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal const val LABEL_WIDTH = 48
internal const val CELL_WIDTH = 128

internal data class SheetPreview(
    val title: String,
    val rows: List<ContentBlock.TableRow>,
    val runs: Map<Pair<Int, Int>, List<TextSpan>>,
    val merges: List<ContentBlock.Merge>,
) {
    val columns: Int = maxOf(
        rows.maxOfOrNull { maxOf(it.cells.size, it.styles.size) } ?: 0,
        (merges.maxOfOrNull { it.lastColumn.coerceIn(0, 16_383) } ?: 0) + 1,
    )

    fun columnCount(): Int = columns
}

private data class WorkbookPreview(val sheets: List<SheetPreview>, val palette: Map<Int, CellLook>)

private fun toWorkbook(blocks: List<ContentBlock>): WorkbookPreview {
    val palette = mutableMapOf<Int, CellLook>()
    val sheets = mutableListOf<SheetPreview>()
    val rows = mutableListOf<ContentBlock.TableRow>()
    val merges = mutableListOf<ContentBlock.Merge>()
    val runs = mutableMapOf<Pair<Int, Int>, List<TextSpan>>()
    var title = ""
    fun finish() {
        if (rows.isNotEmpty()) sheets.add(SheetPreview(title.ifBlank { "Sheet ${sheets.size + 1}" }, rows.toList(), runs.toMap(), merges.toList()))
        rows.clear()
        merges.clear()
        runs.clear()
    }
    for (block in blocks) {
        when (block) {
            is ContentBlock.Section -> {
                finish()
                title = block.title
            }
            is ContentBlock.TableRow -> rows.add(block)
            is ContentBlock.CellStyle -> palette[block.index] = block.look
            is ContentBlock.Merge -> merges.add(block)
            is ContentBlock.CellRuns -> runs[block.row to block.column] = block.spans
            else -> Unit
        }
    }
    finish()
    return WorkbookPreview(sheets, palette)
}

internal fun columnName(index: Int): String = buildString {
    var column = index + 1
    while (column > 0) {
        column--
        append('A' + (column % 26))
        column /= 26
    }
    reverse()
}
