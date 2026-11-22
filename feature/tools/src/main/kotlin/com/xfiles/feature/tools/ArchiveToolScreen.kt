package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzSwitch
import com.xfiles.design.EzTextButton
import com.xfiles.design.EzTopBar
import com.xfiles.design.PrimaryActionButton
import com.xfiles.design.EzShapes
import com.xfiles.design.glassCard
import com.xfiles.design.rememberHaptics
import com.xfiles.design.EzSectionPill
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.xfiles.core.documents.ZipJobProgress
import com.xfiles.core.documents.ZipEntryInfo

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed")

/** Compress to ZIP or Extract, chosen by the route. The route owns the system pickers; the content is stateless. */
@Composable
fun ArchiveToolScreen(
    mode: ArchiveMode,
    onBack: () -> Unit,
    viewModel: ArchiveToolViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(mode) { viewModel.onEvent(ArchiveEvent.Start(mode)) }
    val context = LocalContext.current
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.onEvent(ArchiveEvent.PickSources(it))
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onEvent(ArchiveEvent.PickSourceFolder(it))
    }
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.onEvent(ArchiveEvent.PickArchive(uri, displayNameOf(context, uri)))
    }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.onEvent(ArchiveEvent.PickDestination(uri))
    }
    ArchiveToolContent(
        state = state,
        onBack = onBack,
        onEvent = viewModel::onEvent,
        onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
        onPickFolder = { pickFolder.launch(null) },
        onPickArchive = { pickArchive.launch(ZIP_TYPES) },
        onPickDestination = { pickDestination.launch(null) },
    )
}

@Composable
fun ArchiveToolContent(
    state: ArchiveUiState,
    onBack: () -> Unit,
    onEvent: (ArchiveEvent) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onPickArchive: () -> Unit,
    onPickDestination: () -> Unit,
) {
    val title = if (state.mode == ArchiveMode.Compress) "Compress to ZIP" else "Extract"
    Column(Modifier.fillMaxSize()) {
        EzTopBar(title = title, onBack = onBack)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.mode == ArchiveMode.Compress) {
                item("compress") { CompressSection(state, onEvent, onPickFiles, onPickFolder) }
            } else {
                item("extract") { ExtractSection(state, onEvent, onPickArchive) }
                if (state.entries.isNotEmpty()) {
                    item("entries") { EntriesSection(state.entries, state.selected, onEvent) }
                }
            }
            item("destination") { DestinationSection(state, onPickDestination) }
            item("status") { StatusSection(state, onEvent) }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (state.running) {
                EzTextButton(text = "Cancel", onClick = { onEvent(ArchiveEvent.Cancel) }, modifier = Modifier.fillMaxWidth())
            } else {
                PrimaryActionButton(
                    text = if (state.mode == ArchiveMode.Compress) "Create ZIP" else "Extract",
                    icon = if (state.mode == ArchiveMode.Compress) EzIcons.Archive else EzIcons.Folder,
                    onClick = { onEvent(ArchiveEvent.Run) },
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CompressSection(
    state: ArchiveUiState,
    onEvent: (ArchiveEvent) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val chosen = when {
        state.sourceFolder != null -> "1 folder"
        state.sources.isNotEmpty() -> "${state.sources.size} file${if (state.sources.size == 1) "" else "s"}"
        else -> "Nothing picked yet"
    }
    Column(Modifier.fillMaxWidth().glassCard(EzShapes.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EzSectionPill("Items")
        Text(chosen, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EzTextButton(text = "Pick files", onClick = onPickFiles)
            EzTextButton(text = "Pick a folder", onClick = onPickFolder)
        }
        Text("Output name", color = EzColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
        androidx.compose.material3.OutlinedTextField(
            value = state.outputName,
            onValueChange = { onEvent(ArchiveEvent.SetOutputName(it)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExtractSection(state: ArchiveUiState, onEvent: (ArchiveEvent) -> Unit, onPickArchive: () -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard(EzShapes.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EzSectionPill("Archive")
        Text(
            state.archiveName.ifEmpty { "Pick a ZIP file" },
            color = EzColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        EzTextButton(text = "Pick ZIP", onClick = onPickArchive)
    }
}

@Composable
private fun EntriesSection(entries: List<ZipEntryInfo>, selected: Set<String>, onEvent: (ArchiveEvent) -> Unit) {
    val haptics = rememberHaptics()
    val files = entries.filter { !it.isDir }
    Column(Modifier.fillMaxWidth().glassCard(EzShapes.Card).padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${selected.size} of ${files.size} selected", color = EzColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            EzSwitch(
                checked = selected.size == files.size && files.isNotEmpty(),
                onCheckedChange = { onEvent(ArchiveEvent.SelectAll(it)) },
            )
        }
        files.forEach { e ->
            val on = e.name in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Checkbox) {
                        haptics.toggle(!on)
                        onEvent(ArchiveEvent.ToggleEntry(e.name))
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = EzColors.Text, style = MaterialTheme.typography.bodyMedium)
                    Text(formatBytes(e.size), color = EzColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
                EzSwitch(checked = on, onCheckedChange = { onEvent(ArchiveEvent.ToggleEntry(e.name)) })
            }
        }
    }
}

@Composable
private fun DestinationSection(state: ArchiveUiState, onPickDestination: () -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard(EzShapes.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EzSectionPill("Save to")
        Text(
            if (state.destination == null) "No folder chosen" else "Folder chosen",
            color = EzColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        EzTextButton(text = "Choose folder", onClick = onPickDestination)
    }
}

@Composable
private fun StatusSection(state: ArchiveUiState, onEvent: (ArchiveEvent) -> Unit) {
    val progress = state.progress
    when {
        state.running -> Column(Modifier.fillMaxWidth().glassCard(EzShapes.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val fraction = progressFraction(progress)
            if (fraction == null) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                progress?.let { "${it.entriesDone} of ${it.entriesTotal} files · ${it.current.substringAfterLast('/')}" } ?: "Starting",
                color = EzColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.resultMessage != null -> Row(
            Modifier.fillMaxWidth().glassCard(EzShapes.Card).padding(16.dp).clickable { onEvent(ArchiveEvent.DismissResult) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(state.resultMessage, color = EzColors.Text, fontWeight = FontWeight.SemiBold)
        }
        state.error != null -> Text(state.error, color = EzColors.Accent, modifier = Modifier.padding(horizontal = 4.dp), fontSize = 14.sp)
    }
}

private fun progressFraction(p: ZipJobProgress?): Float? {
    if (p == null) return null
    if (p.entriesTotal == 0) return null
    return (p.entriesDone.toFloat() / p.entriesTotal).coerceIn(0f, 1f)
}


private fun displayNameOf(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "archive.zip")
