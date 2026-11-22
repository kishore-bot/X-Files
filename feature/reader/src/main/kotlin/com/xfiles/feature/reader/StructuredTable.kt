package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xfiles.documents.structure.ContentBlock

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
internal fun DocumentTable(rows: List<ContentBlock.TableRow>) {
    val dark = themeIsDark()
    val columns = remember(rows) { rows.maxOf { maxOf(it.cells.size, 1) } }
    val widths = remember(rows) { columnWidths(rows, columns) }
    val line = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(pageSurface(MaterialTheme.colorScheme.surface))
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(Modifier.border(1.dp, line, shape)) {
            rows.forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (column in 0 until columns) {
                        val cell = row.cells.getOrNull(column).orEmpty()
                        val spans = row.cellSpans.getOrNull(column).orEmpty()
                        val styled = remember(cell, spans, dark) { richText(cell, spans, dark) }
                        Box(
                            Modifier.width(widths[column])
                                .fillMaxHeight()
                                .border(0.5.dp, line)
                                .then(if (row.header) Modifier.background(pageVariant(MaterialTheme.colorScheme.surfaceVariant)) else Modifier)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            Text(
                                styled,
                                fontWeight = if (row.header) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Width per column from the longest cell in it, clamped so one wide column can't swallow the screen. */
private fun columnWidths(rows: List<ContentBlock.TableRow>, columns: Int): List<Dp> = List(columns) { column ->
    val longest = rows.maxOf { row -> row.cells.getOrNull(column)?.length ?: 0 }
    (longest * 7 + 28).coerceIn(96, 260).dp
}
