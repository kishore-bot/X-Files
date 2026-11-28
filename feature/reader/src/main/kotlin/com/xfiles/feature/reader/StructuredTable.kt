package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import bot.lazymatrix.LazyMatrixX
import bot.lazymatrix.rememberGridScrollState
import com.xfiles.documents.structure.ContentBlock
import kotlinx.coroutines.flow.first

/** One entry of a document: a single block, or a table made of consecutive table rows. */
internal sealed interface DocumentItem {
    data class Single(val block: ContentBlock) : DocumentItem

    data class Table(val rows: List<ContentBlock.TableRow>) : DocumentItem
}

/** Groups each run of consecutive [ContentBlock.TableRow]s into one [DocumentItem.Table]. */
internal fun documentItems(blocks: List<ContentBlock>): List<DocumentItem> {
    val items = ArrayList<DocumentItem>(blocks.size)
    var run = ArrayList<ContentBlock.TableRow>()
    for (block in blocks) {
        if (block is ContentBlock.TableRow) {
            run.add(block)
        } else {
            if (run.isNotEmpty()) items.add(DocumentItem.Table(run.toList()).also { run = ArrayList() })
            items.add(DocumentItem.Single(block))
        }
    }
    if (run.isNotEmpty()) items.add(DocumentItem.Table(run.toList()))
    return items
}

/**
 * A whole table as one block: a single grid whose columns share one width per column across every row, with
 * a header style on header rows and hairline borders. The grid scrolls sideways as a unit, so rows never
 * drift out of alignment with each other.
 */
@Composable
internal fun DocumentTable(rows: List<ContentBlock.TableRow>, progressField: String? = null) {
    val columns = remember(rows) { rows.maxOf { maxOf(it.cells.size, 1) } }
    val widths = remember(rows) { columnWidths(rows, columns) }
    val palette = LocalReaderColors.current
    val shape = RoundedCornerShape(10.dp)
    val totalWidth = remember(widths) { widths.fold(0.dp) { total, width -> total + width } }
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(shape)
            .background(palette.page).border(1.dp, palette.grid, shape),
    ) {
        if (totalWidth > maxWidth && progressField != null) {
            WideDocumentTable(rows, widths, progressField)
        } else {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        widths.forEachIndexed { column, width ->
                            DocumentTableCell(row, column, Modifier.width(width).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WideDocumentTable(rows: List<ContentBlock.TableRow>, widths: List<Dp>, progressField: String) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val rowHeights = remember(rows, widths, density, style, textMeasurer) {
        with(density) {
            rows.map { row ->
                val rowStyle = style.copy(fontWeight = if (row.header) FontWeight.Bold else FontWeight.Normal)
                val height = widths.indices.maxOf { column ->
                    val text = richText(row.cells.getOrNull(column).orEmpty(), row.cellSpans.getOrNull(column).orEmpty())
                    textMeasurer.measure(
                        text, style = rowStyle,
                        constraints = Constraints(maxWidth = (widths[column] - 20.dp).roundToPx().coerceAtLeast(1)),
                    ).size.height
                }
                height + 7.dp.roundToPx() * 2
            }
        }
    }
    val columnWidths = remember(widths) { widths.mapIndexed { index, width -> index.toLong() to width }.toMap() }
    val state = rememberGridScrollState(1L, widths.size.toLong(), columnWidthOverrides = columnWidths)
    val progress = LocalViewerProgress.current
    var restored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state, progressField, progress) {
        if (!restored) {
            snapshotFlow { state.isReady }.first { it }
            val previous = progress?.read(progressField)?.toLongOrNull() ?: 0L
            state.scrollTo(previous, 0L)
            restored = true
        }
    }
    SaveViewerProgress(progressField) { state.scrollX.toString().takeIf { restored && state.isReady } }

    // Bound each pane independently while the document controls vertical scrolling.
    val bands = remember(rowHeights, density) {
        val limit = with(density) { 960.dp.roundToPx() }
        buildList {
            var firstRow = 0
            var height = 0
            rowHeights.forEachIndexed { index, rowHeight ->
                if (index > firstRow && height + rowHeight > limit) {
                    add(DocumentTableBand(firstRow until index, height))
                    firstRow = index
                    height = 0
                }
                height += rowHeight
            }
            add(DocumentTableBand(firstRow until rowHeights.size, height))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        bands.forEach { band ->
            val height = with(density) { band.height.toDp() }
            LazyMatrixX(state, Modifier.fillMaxWidth().height(height)) { _, column ->
                Column(Modifier.matrixPaneContent(height = band.height)) {
                    band.rows.forEach { row ->
                        DocumentTableCell(
                            rows[row],
                            column.toInt(),
                            Modifier.fillMaxWidth().height(with(density) { rowHeights[row].toDp() }),
                        )
                    }
                }
            }
        }
    }
}

private data class DocumentTableBand(val rows: IntRange, val height: Int)

@Composable
private fun DocumentTableCell(row: ContentBlock.TableRow, column: Int, modifier: Modifier) {
    val palette = LocalReaderColors.current
    val background = if (row.header) palette.header else palette.page
    val cell = row.cells.getOrNull(column).orEmpty()
    val spans = row.cellSpans.getOrNull(column).orEmpty()
    val styled = remember(cell, spans, palette, background) { richText(cell, spans, palette = palette, background = background) }
    Box(
        modifier.background(background).border(0.5.dp, palette.grid)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            styled,
            fontWeight = if (row.header) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Width per column from the longest cell in it, clamped so one wide column can't swallow the screen. */
private fun columnWidths(rows: List<ContentBlock.TableRow>, columns: Int): List<Dp> = List(columns) { column ->
    val longest = rows.maxOf { row -> row.cells.getOrNull(column)?.length ?: 0 }
    (longest * 7 + 28).coerceIn(96, 260).dp
}
