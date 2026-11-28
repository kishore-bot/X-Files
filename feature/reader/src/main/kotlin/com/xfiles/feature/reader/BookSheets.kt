package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.BarIconButton
import com.xfiles.design.EzChip
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.documents.book.BookManifest
import com.xfiles.documents.book.HighlightColor
import com.xfiles.documents.book.NotebookEntry
import com.xfiles.documents.book.NotebookKind

/** A solid colour for swatches and marks: the reader's four, as the EzFiles reader draws them. */
internal fun HighlightColor.swatch(): Color = when (this) {
    HighlightColor.Yellow -> Color(0xFFFFD84D)
    HighlightColor.Green -> Color(0xFF7FD99A)
    HighlightColor.Blue -> Color(0xFF7FB8FF)
    HighlightColor.Pink -> Color(0xFFFF8FBF)
}

/** A line of the table of contents, and the block it opens at (null when the book has no such place). */
private class ContentsRow(val title: String, val depth: Int, val block: Int?)

private fun contentsRows(manifest: BookManifest?): List<ContentsRow> {
    val toc = manifest?.toc.orEmpty()
    if (toc.isNotEmpty()) return toc.map { ContentsRow(it.title, it.depth, manifest?.blockOf(it.href)) }
    return manifest?.chapters.orEmpty().withIndex()
        .filter { it.value.firstBlock != null }
        .map { (index, chapter) -> ContentsRow(manifest?.chapterTitle(index).orEmpty(), 0, chapter.firstBlock) }
}

/**
 * Contents and Notebook in one sheet, as the EzFiles reader has them: the book's table of contents,
 * and every bookmark, highlight and note in reading order. [notebook] opens it on the Notebook tab.
 */
@Composable
internal fun BookContentsSheet(
    manifest: BookManifest?,
    entries: List<NotebookEntry>,
    notebook: Boolean,
    current: Int,
    onJump: (Int) -> Unit,
    onDelete: (NotebookEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var onNotebook by remember(notebook) { mutableStateOf(notebook) }
    val rows = remember(manifest) { contentsRows(manifest) }
    val active = rows.indexOfLast { row -> row.block?.let { it <= current } == true }
    BookSheetFrame(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                manifest?.title?.takeIf { it.isNotBlank() } ?: "Contents",
                style = MaterialTheme.typography.titleMedium,
                color = EzColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EzChip("Contents", selected = !onNotebook, onClick = { onNotebook = false })
                EzChip(
                    if (entries.isEmpty()) "Notebook" else "Notebook (${entries.size})",
                    selected = onNotebook,
                    onClick = { onNotebook = true },
                )
            }
        }
        if (!onNotebook) {
            ContentsList(rows, active, onJump)
        } else {
            NotebookList(entries, onJump, onDelete)
        }
    }
}

@Composable
private fun ContentsList(rows: List<ContentsRow>, active: Int, onJump: (Int) -> Unit) {
    if (rows.isEmpty()) {
        Text(
            "This book has no contents.",
            style = MaterialTheme.typography.bodyMedium,
            color = EzColors.TextSecondary,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 8.dp),
        state = rememberLazyListState(initialFirstVisibleItemIndex = (active - 3).coerceAtLeast(0)),
    ) {
        itemsIndexed(rows) { index, row ->
            val selected = index == active
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else if (row.depth == 0) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) EzColors.Accent else EzColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) EzColors.Primary.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable(enabled = row.block != null, role = Role.Button) { row.block?.let(onJump) }
                    .padding(start = (24 + 18 * row.depth.coerceAtMost(4)).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun NotebookList(entries: List<NotebookEntry>, onJump: (Int) -> Unit, onDelete: (NotebookEntry) -> Unit) {
    if (entries.isEmpty()) {
        Text(
            "Nothing marked yet. Press and hold text to highlight it or add a note, and use the bookmark in the top bar to mark a page.",
            style = MaterialTheme.typography.bodyMedium,
            color = EzColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 8.dp)) {
        items(entries, key = { it.kind.name + it.id }) { entry ->
            NotebookRow(entry, onOpen = { onJump(entry.block) }, onDelete = { onDelete(entry) })
        }
    }
}

/** One mark in the notebook: a bookmark, or a highlight with its note. A tap goes there. */
@Composable
private fun NotebookRow(entry: NotebookEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val label = when (entry.kind) {
        NotebookKind.Bookmark -> "Bookmark"
        NotebookKind.Highlight -> "Highlight"
        NotebookKind.Note -> "Note"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Go there") { onOpen() }
            .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (entry.kind == NotebookKind.Bookmark) {
            Icon(EzIcons.Bookmark, null, tint = EzColors.Primary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        } else {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(width = 4.dp, height = 36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(entry.color?.swatch() ?: EzColors.Accent),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = EzColors.TextSecondary, maxLines = 1)
            Text(
                entry.title.ifBlank { label },
                style = MaterialTheme.typography.bodyMedium,
                color = EzColors.Text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.detail.isNotBlank()) {
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = EzColors.Accent,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BarIconButton(EzIcons.Delete, "Remove $label", onDelete)
    }
}

/** A scrolling row of EzChips that picks one value; each chip gives its own haptic cue. */
@Composable
internal fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, name) ->
            EzChip(name, selected = value == selected, onClick = { onSelect(value) })
        }
    }
}
