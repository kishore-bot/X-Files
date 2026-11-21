package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import com.xfiles.documents.structure.CellAlign
import com.xfiles.documents.structure.CellLook
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.StructuredDocument
import com.xfiles.documents.structure.TextSpan

@Composable
internal fun SpreadsheetPreview(document: StructuredDocument) {
    val workbook = remember(document.blocks) { toWorkbook(document.blocks) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val sheet = workbook.sheets.getOrNull(selected) ?: workbook.sheets.firstOrNull()
    if (sheet == null) {
        Text("This workbook has no readable rows.")
        return
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (workbook.sheets.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                workbook.sheets.forEachIndexed { index, candidate ->
                    FilterChip(
                        selected = candidate === sheet,
                        onClick = { selected = index },
                        label = { Text(candidate.title, overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    )
                }
            }
        }
        if (document.truncated) Text("Showing the rows within the preview limit.", color = EzColors.TextSecondary)
        key(sheet) { SheetGrid(sheet, workbook.palette) }
    }
}

@Composable
private fun SheetGrid(sheet: SheetPreview, palette: Map<Int, CellLook>) {
    val columns = sheet.rows.maxOfOrNull { maxOf(it.cells.size, it.styles.size) }?.coerceAtLeast(1) ?: 1
    var firstColumn by rememberSaveable { mutableIntStateOf(0) }
    val start = firstColumn.coerceIn(0, columns - 1)
    val end = (start + COLUMNS_PER_VIEW).coerceAtMost(columns)
    val horizontal = rememberScrollState()

    if (columns > COLUMNS_PER_VIEW) {
        Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { firstColumn = (start - COLUMNS_PER_VIEW).coerceAtLeast(0) }, enabled = start > 0) {
                Icon(EzIcons.ChevronLeft, "Previous columns")
            }
            Text("${columnName(start)}–${columnName(end - 1)}")
            IconButton(onClick = { firstColumn = end }, enabled = end < columns) {
                Icon(EzIcons.ChevronRight, "Next columns")
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().horizontalScroll(horizontal).width(48.dp + 128.dp * (end - start)),
    ) {
        item("columns") {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                GridLabel("", 48)
                for (column in start until end) GridLabel(columnName(column), 128)
            }
        }
        itemsIndexed(sheet.rows, key = { index, _ -> index }) { index, row ->
            val number = row.row ?: index + 1
            Row(Modifier.background(MaterialTheme.colorScheme.surface)) {
                GridLabel(number.toString(), 48)
                for (column in start until end) {
                    val text = row.cells.getOrNull(column).orEmpty()
                    val look = row.styles.getOrNull(column)?.let(palette::get)
                    val spans = sheet.runs[number to column].orEmpty()
                    SpreadsheetCell(text, look, spans, row.header)
                }
            }
        }
    }
}

@Composable
private fun GridLabel(label: String, width: Int) {
    Box(
        Modifier.width(width.dp).heightIn(min = 34.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = EzColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SpreadsheetCell(value: String, look: CellLook?, spans: List<TextSpan>, header: Boolean) {
    val align = when (look?.align) {
        CellAlign.Right -> TextAlign.End
        CellAlign.Center -> TextAlign.Center
        CellAlign.Left -> TextAlign.Start
        else -> if (value.toDoubleOrNull() != null) TextAlign.End else TextAlign.Start
    }
    val fill = look?.fill?.let(::Color)
        ?: if (header) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    val foreground = look?.color?.let(::Color) ?: if (look?.fill != null) {
        if (fill.luminance() > 0.38f) Color.Black else Color.White
    } else {
        EzColors.Text
    }
    val underline = buildList {
        if (look?.underline == true) add(TextDecoration.Underline)
        if (look?.strikethrough == true) add(TextDecoration.LineThrough)
    }.takeIf { it.isNotEmpty() }?.let(TextDecoration::combine)
    val content = remember(value, spans) { richText(value, spans) }
    Box(
        Modifier.width(128.dp).heightIn(min = 34.dp)
            .background(fill)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        SelectionContainer {
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
}

private const val COLUMNS_PER_VIEW = 16

private data class SheetPreview(
    val title: String,
    val rows: List<ContentBlock.TableRow>,
    val runs: Map<Pair<Int, Int>, List<TextSpan>>,
)

private data class WorkbookPreview(val sheets: List<SheetPreview>, val palette: Map<Int, CellLook>)

private fun toWorkbook(blocks: List<ContentBlock>): WorkbookPreview {
    val palette = mutableMapOf<Int, CellLook>()
    val sheets = mutableListOf<SheetPreview>()
    val rows = mutableListOf<ContentBlock.TableRow>()
    val runs = mutableMapOf<Pair<Int, Int>, List<TextSpan>>()
    var title = ""
    fun finish() {
        if (rows.isNotEmpty()) sheets.add(SheetPreview(title.ifBlank { "Sheet ${sheets.size + 1}" }, rows.toList(), runs.toMap()))
        rows.clear()
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
            is ContentBlock.CellRuns -> runs[block.row to block.column] = block.spans
            else -> Unit
        }
    }
    finish()
    return WorkbookPreview(sheets, palette)
}

private fun columnName(index: Int): String = buildString {
    var column = index + 1
    while (column > 0) {
        column--
        append('A' + (column % 26))
        column /= 26
    }
    reverse()
}
