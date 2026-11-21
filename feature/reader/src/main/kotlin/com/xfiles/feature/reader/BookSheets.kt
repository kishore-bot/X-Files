@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.documents.book.BookManifest
import com.xfiles.documents.book.BookNote
import com.xfiles.documents.book.Highlight
import com.xfiles.documents.book.HighlightColor
import com.xfiles.documents.book.LineSpacing
import com.xfiles.documents.book.NotebookEntry
import com.xfiles.documents.book.NotebookKind
import com.xfiles.documents.book.PageMargin
import com.xfiles.documents.book.ReadingFont
import com.xfiles.documents.book.ReadingSettings
import com.xfiles.documents.book.ReadingTheme

/** The table of contents from the book's navigation file, or its chapters when it has none. */
@Composable
internal fun BookContentsSheet(manifest: BookManifest?, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val entries = manifest?.toc ?: emptyList()
        if (entries.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(entries) { entry ->
                    val block = manifest?.blockOf(entry.href)
                    SheetRow(
                        title = entry.title,
                        detail = null,
                        indent = entry.depth * 16,
                        enabled = block != null,
                        onClick = { block?.let(onJump) },
                    )
                }
            }
        } else {
            val chapters = (manifest?.chapters ?: emptyList()).withIndex().filter { it.value.firstBlock != null }
            if (chapters.isEmpty()) {
                Text("This book has no contents.", modifier = Modifier.padding(24.dp), color = EzColors.TextSecondary)
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    items(chapters) { (index, chapter) ->
                        SheetRow(
                            title = manifest?.chapterTitle(index) ?: "",
                            detail = null,
                            indent = 0,
                            enabled = true,
                            onClick = { chapter.firstBlock?.let(onJump) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Theme, font, size, line spacing and margins: the "Aa" sheet. Every change applies at once, to the
 * settings this book reads with. [ownSettings] says whether the book has its own, which [onOwnSettings]
 * turns on (a copy of the current settings) or off (back to the global ones). [onReset] puts the settings back to the defaults.
 */
@Composable
internal fun BookSettingsSheet(
    settings: ReadingSettings,
    ownSettings: Boolean,
    onChange: (ReadingSettings) -> Unit,
    onOwnSettings: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use for this book only", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (ownSettings) "This book keeps its own settings." else "This book follows the global settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                    )
                }
                Switch(checked = ownSettings, onCheckedChange = onOwnSettings)
            }
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingTheme.entries.forEach { theme ->
                    val paint = bookPalette(theme)
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(paint.background)
                            .border(if (settings.theme == theme) 2.dp else 1.dp, if (settings.theme == theme) EzColors.Primary else EzColors.TextSecondary.copy(alpha = 0.4f), CircleShape)
                            .clickable { onChange(settings.copy(theme = theme)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aa", color = paint.text, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Text("Font", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingFont.entries.forEach { font ->
                    FilterChip(
                        selected = settings.font == font,
                        onClick = { onChange(settings.copy(font = font)) },
                        label = { Text(font.label()) },
                    )
                }
            }
            Text("Text size", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onChange(settings.withTextSize(settings.textSizeSp - 1)) },
                    enabled = settings.textSizeSp > ReadingSettings.MIN_TEXT_SIZE,
                ) { Icon(EzIcons.Minus, "Smaller text") }
                Text("${settings.textSizeSp}", modifier = Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(
                    onClick = { onChange(settings.withTextSize(settings.textSizeSp + 1)) },
                    enabled = settings.textSizeSp < ReadingSettings.MAX_TEXT_SIZE,
                ) { Icon(EzIcons.Plus, "Larger text") }
            }
            Text("Line spacing", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineSpacing.entries.forEach { spacing ->
                    FilterChip(
                        selected = settings.lineSpacing == spacing,
                        onClick = { onChange(settings.copy(lineSpacing = spacing)) },
                        label = { Text(spacing.name) },
                    )
                }
            }
            Text("Margins", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageMargin.entries.forEach { margin ->
                    FilterChip(
                        selected = settings.margin == margin,
                        onClick = { onChange(settings.copy(margin = margin)) },
                        label = { Text(margin.name) },
                    )
                }
            }
            TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) { Text("Reset to default") }
        }
    }
}

/** Every bookmark, highlight and note, in reading order. A tap jumps to it. */
@Composable
internal fun BookNotebookSheet(
    entries: List<NotebookEntry>,
    onJump: (Int) -> Unit,
    onDelete: (NotebookEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (entries.isEmpty()) {
            Text(
                "Nothing marked yet. Long-press a word to highlight or note it, or tap the bookmark icon for a place.",
                modifier = Modifier.padding(24.dp),
                color = EzColors.TextSecondary,
            )
            return@ModalBottomSheet
        }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(entries, key = { it.kind.name + it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth().clickable { onJump(entry.block) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val (icon, tint) = when (entry.kind) {
                        NotebookKind.Bookmark -> EzIcons.Bookmark to EzColors.Primary
                        NotebookKind.Highlight -> EzIcons.Edit to (entry.color?.swatch() ?: EzColors.Accent)
                        NotebookKind.Note -> EzIcons.NoteAdd to EzColors.Accent
                    }
                    Icon(icon, null, tint = tint)
                    Column(Modifier.weight(1f)) {
                        Text(entry.title.ifBlank { "Bookmark" }, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        if (entry.detail.isNotBlank()) {
                            Text(entry.detail, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { onDelete(entry) }) { Icon(EzIcons.Delete, "Remove") }
                }
            }
        }
    }
}

@Composable
internal fun BookFootnoteSheet(label: String, text: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label.ifBlank { "Note" }, style = MaterialTheme.typography.titleSmall, color = EzColors.Primary)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
internal fun BookNoteSheet(quote: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        var text by rememberSaveable(quote) { mutableStateOf(initial) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("“${quote.take(160)}”", style = MaterialTheme.typography.bodySmall, maxLines = 3, color = EzColors.TextSecondary)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                placeholder = { Text("Your note") },
            )
            Button(
                onClick = { onSave(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) { Text("Save") }
        }
    }
}

/** A highlight or note the reader tapped: its colour, its note, and removal. */
@Composable
internal fun BookMarkedSheet(
    highlight: Highlight?,
    note: BookNote?,
    onColor: (HighlightColor) -> Unit,
    onEditNote: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val quote = highlight?.quote ?: note?.quote ?: ""
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("“${quote.take(160)}”", style = MaterialTheme.typography.bodyMedium, maxLines = 4, color = EzColors.TextSecondary)
            note?.let { Text(it.text, style = MaterialTheme.typography.bodyLarge) }
            if (highlight != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HighlightColor.entries.forEach { color ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(color.swatch())
                                .border(if (highlight.color == color) 2.dp else 0.dp, EzColors.Primary, CircleShape)
                                .clickable { onColor(color) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (note != null) TextButton(onClick = onEditNote) { Text("Edit note") }
                TextButton(onClick = onDelete) { Text(if (note != null && highlight == null) "Remove note" else "Remove highlight") }
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, detail: String?, indent: Int, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 20.dp + indent.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) EzColors.Text else EzColors.TextSecondary)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
    }
}

private fun ReadingFont.label(): String = when (this) {
    ReadingFont.Serif -> "Serif"
    ReadingFont.Sans -> "Sans"
    ReadingFont.Mono -> "Mono"
}

/** A solid colour for swatches; the translucent paint is for the page. */
internal fun HighlightColor.swatch(): Color = paint(onDark = false).copy(alpha = 1f)
