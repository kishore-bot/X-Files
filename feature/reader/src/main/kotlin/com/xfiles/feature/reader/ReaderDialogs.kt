@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xfiles.feature.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzControlSheet
import com.xfiles.design.LocalEzDarkTheme
import com.xfiles.core.documents.FileEntry
import com.xfiles.design.rememberHaptics

/** The password prompt for an encrypted document; a wrong password keeps it open with an error. */
@Composable
internal fun DocumentPasswordDialog(state: ReaderState.Password, onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    val password = remember(state.file.uri, state.incorrect) { TextFieldState() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock document") },
        text = {
            OutlinedSecureTextField(
                state = password,
                label = { Text("Password") },
                isError = state.incorrect,
                supportingText = if (state.incorrect) ({ Text("That password did not unlock the file.") }) else null,
            )
        },
        confirmButton = {
            TextButton(onClick = { onUnlock(password.text.toString()) }, enabled = password.text.isNotEmpty()) {
                Text("Unlock")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Hands a file the reader can't show to another app; the chooser lists the apps that can open it. */
@Composable
internal fun ExternalOpen(file: FileEntry, message: String = "An in-app preview is unavailable for this file.") {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message)
        TextButton(onClick = {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, context.contentResolver.getType(file.uri) ?: "application/octet-stream")
                clipData = ClipData.newRawUri(file.name, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Open with"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                // The app that shared the file didn't let Xfiles pass it on.
                Toast.makeText(context, "This file can't be passed to another app. Open it from where it's stored.", Toast.LENGTH_LONG).show()
            }
        }) { Text("Open with") }
    }
}

/** "At a glance": word count, reading time, key sentences and keywords, with references that jump in the file. */
@Composable
internal fun SummaryDialog(
    state: SummaryState,
    documentName: String,
    onReference: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("At a glance") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    SummaryState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    SummaryState.Error -> Text("This document could not be summarized.")
                    is SummaryState.Ready -> {
                        val summary = state.analysis
                        if (summary.wordCount > 0u) {
                            Text("${summary.wordCount} words · ${summary.readingMinutes} min read")
                        }
                        if (summary.items.isNotEmpty()) GlanceItemGroups(summary.items, documentName, onReference)
                        if (summary.keySentences.isEmpty() && summary.keywords.isEmpty() && summary.items.isEmpty()) {
                            Text("No summary is available for this text.")
                        }
                        summary.keySentences.forEach { Text(it) }
                        if (summary.keywords.isNotEmpty()) Text(summary.keywords.joinToString(", "))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageStyleSheet(current: PageStyle, onPick: (PageStyle) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    EzControlSheet(onDismiss) {
        val darkApp = LocalEzDarkTheme.current
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Page style", style = MaterialTheme.typography.titleMedium)
            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageStyle.entries.forEach { style ->
                    val selected = style == current
                    val fixed = style.readerColors(darkApp)
                    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    Column(
                        modifier = Modifier.selectable(selected, role = Role.RadioButton) {
                            haptics.segment()
                            onPick(style)
                        }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape)
                                .background(fixed.page)
                                .border(2.dp, ring, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Aa", color = fixed.text, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(style.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
