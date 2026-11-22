package com.xfiles.feature.tools

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.ZipEntryInfo
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzListItem
import com.xfiles.design.EzSectionHeader
import com.xfiles.design.EzShapes
import com.xfiles.design.EzSwitch
import com.xfiles.design.EzTextButton
import com.xfiles.design.glassCard

/** The compress inputs: the picked files as reorderable cards, or the one folder. */
internal fun LazyListScope.compressSources(
    state: ArchiveUiState,
    onEvent: (ArchiveEvent) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val folder = state.sourceFolder
    if (folder != null) {
        item("folder") {
            ToolFileRow(
                name = folderLabel(folder),
                meta = "Everything inside, as it is",
                onRemove = { onEvent(ArchiveEvent.PickSourceFolder(null)) },
            )
        }
    } else {
        val files = state.sources
        itemsIndexed(files, key = { _, uri -> uri.toString() }) { index, uri ->
            val context = LocalContext.current
            val name = remember(uri) { archiveDisplayName(context, uri) }
            val moveUp: (() -> Unit)? = if (index > 0) ({ onEvent(ArchiveEvent.PickSources(files.swap(index, index - 1))) }) else null
            val moveDown: (() -> Unit)? = if (index < files.lastIndex) ({ onEvent(ArchiveEvent.PickSources(files.swap(index, index + 1))) }) else null
            PickedFileCard(
                name = name,
                meta = "File ${index + 1} of ${files.size}",
                thumbnail = null,
                onRemove = { onEvent(ArchiveEvent.PickSources(files - uri)) },
                onMoveUp = moveUp,
                onMoveDown = moveDown,
            )
        }
    }
    item("replace") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EzTextButton(text = "Choose files again", onClick = onPickFiles)
            EzTextButton(text = "Pick a folder", onClick = onPickFolder)
        }
    }
}

/** The output name, as a field with the .zip ending the job adds when missing. */
internal fun LazyListScope.outputName(state: ArchiveUiState, onEvent: (ArchiveEvent) -> Unit) {
    item("output") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EzSectionHeader("Name the ZIP")
            OutlinedTextField(
                value = state.outputName,
                onValueChange = { onEvent(ArchiveEvent.SetOutputName(it)) },
                singleLine = true,
                label = { Text("File name") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The archive being extracted, its entries as selectable rows with a select-all switch. */
internal fun LazyListScope.extractEntries(
    state: ArchiveUiState,
    entries: List<ZipEntryInfo>,
    onEvent: (ArchiveEvent) -> Unit,
) {
    val files = entries.filter { !it.isDir }
    item("entries-head") {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EzSectionHeader("${state.selected.size} of ${files.size} selected")
            EzSwitch(
                checked = state.selected.size == files.size && files.isNotEmpty(),
                onCheckedChange = { onEvent(ArchiveEvent.SelectAll(it)) },
            )
        }
    }
    items(files, key = { it.name }) { entry ->
        EzListItem(
            title = entry.name,
            supporting = formatBytes(entry.size),
            icon = EzIcons.Document,
            modifier = Modifier.glassCard(EzShapes.Card, 0.45f),
            trailing = {
                EzSwitch(
                    checked = entry.name in state.selected,
                    onCheckedChange = { onEvent(ArchiveEvent.ToggleEntry(entry.name)) },
                )
            },
            onClick = { onEvent(ArchiveEvent.ToggleEntry(entry.name)) },
        )
    }
}

/** Where the output goes: a glass card that says which folder is chosen. */
internal fun LazyListScope.destinationCard(state: ArchiveUiState, onPickDestination: () -> Unit) {
    item("destination") {
        Column(Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.5f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EzSectionHeader("Save to")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(EzIcons.Folder, contentDescription = null, tint = EzColors.Primary, modifier = Modifier.size(24.dp))
                Text(
                    if (state.destination == null) "No folder chosen yet" else "Folder chosen",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.destination == null) EzColors.TextSecondary else EzColors.Text,
                )
            }
            EzTextButton(text = if (state.destination == null) "Choose folder" else "Change folder", onClick = onPickDestination)
        }
    }
}

/** A quiet error line under the sections, in the accent colour. */
internal fun LazyListScope.archiveError(message: String?) {
    if (message == null) return
    item("error") {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = EzColors.Accent, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** The file-like row of a folder source, with a remove control. */
@Composable
private fun ToolFileRow(name: String, meta: String, onRemove: () -> Unit) {
    PickedFileCard(name = name, meta = meta, thumbnail = null, onRemove = onRemove)
}

private fun folderLabel(folder: Uri): String =
    folder.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { null } ?: "Folder"

/** The same list with two positions swapped (the reorder controls). */
private fun List<Uri>.swap(a: Int, b: Int): List<Uri> = toMutableList().also { java.util.Collections.swap(it, a, b) }

internal fun archiveDisplayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "File")
