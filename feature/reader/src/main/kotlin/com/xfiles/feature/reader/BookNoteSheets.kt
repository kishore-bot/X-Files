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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xfiles.design.EzColors
import com.xfiles.design.EzShapes
import com.xfiles.design.EzTextButton
import com.xfiles.design.glassPanel
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.book.BookNote
import com.xfiles.documents.book.Highlight
import com.xfiles.documents.book.HighlightColor

/** The passage a note or highlight is about, on its colour, in the book's serif. */
@Composable
private fun QuoteBox(quote: String, color: Color) {
    Text(
        quote,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Serif,
        color = EzColors.Text,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.35f))
            .padding(12.dp),
    )
}

/** Writes the note on [quote]; focused as soon as it opens. */
@Composable
internal fun BookNoteSheet(quote: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(quote) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    BookSheetFrame(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            QuoteBox(quote, HighlightColor.Yellow.swatch())
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                EzTextButton("Cancel", onDismiss, color = EzColors.TextSecondary)
                Spacer(Modifier.weight(1f))
                EzTextButton("Save", { onSave(text.trim()) }, enabled = text.isNotBlank())
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
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
    val haptics = rememberHaptics()
    val quote = highlight?.quote ?: note?.quote ?: ""
    BookSheetFrame(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            QuoteBox(quote, (highlight?.color ?: HighlightColor.Yellow).swatch())
            note?.let { Text(it.text, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text) }
            if (highlight != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    HighlightColor.entries.forEach { color ->
                        val selected = highlight.color == color
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color.swatch())
                                .border(if (selected) 3.dp else 0.dp, if (selected) EzColors.Text else Color.Transparent, CircleShape)
                                .clickable(role = Role.RadioButton, onClickLabel = color.name) {
                                    haptics.tick()
                                    onColor(color)
                                },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EzTextButton(if (note != null && highlight == null) "Remove note" else "Remove highlight", onDelete)
                Spacer(Modifier.weight(1f))
                if (note != null) EzTextButton("Edit note", onEditNote)
                EzTextButton("Done", onDismiss)
            }
        }
    }
}

/** A footnote, read in a glass popup over the page, as the EzFiles reader shows its notes. */
@Composable
internal fun BookFootnoteSheet(label: String, text: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .glassPanel(EzShapes.Dialog)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label.ifBlank { "Note" }, style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = EzColors.Text,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                EzTextButton("Done", onDismiss)
            }
        }
    }
}
