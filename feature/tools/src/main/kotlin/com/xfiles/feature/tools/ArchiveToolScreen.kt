package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xfiles.core.documents.ZipJobProgress
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzTextButton

private val ARCHIVE_TYPES = arrayOf("*/*")

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
        if (uri != null) viewModel.onEvent(ArchiveEvent.PickArchive(uri, archiveDisplayName(context, uri)))
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
        onPickArchive = { pickArchive.launch(ARCHIVE_TYPES) },
        onPickDestination = { pickDestination.launch(null) },
    )
}

/**
 * The archive flow on the shared skeleton: hero and stepper, pick, adjust (name or entries), save to a folder, and a
 * sticky action bar. After a job, the result screen replaces the body.
 */
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
    val compress = state.mode == ArchiveMode.Compress
    val title = if (compress) "Create archive" else "Extract"
    val hasInput = if (compress) state.sources.isNotEmpty() || state.sourceFolder != null else state.archive != null
    val step = when {
        state.resultMessage != null -> ToolStep.Save
        !hasInput -> ToolStep.Pick
        state.destination == null -> ToolStep.Adjust
        else -> ToolStep.Save
    }
    ToolScaffold(
        title = title,
        backLabel = "Back to library",
        onBack = onBack,
        bottomBar = { ArchiveBottomBar(state, onEvent) },
    ) {
        if (state.resultMessage != null) {
            item("result") {
                ToolResult(
                    title = state.resultMessage,
                    outputName = if (compress) state.outputName else state.archiveName,
                    onDoAnother = { onEvent(ArchiveEvent.DismissResult) },
                )
            }
        } else {
            item("hero") {
                ToolHero(
                    icon = if (compress) EzIcons.Archive else EzIcons.Folder,
                    tint = EzColors.Primary,
                    title = title,
                    blurb = if (compress) {
                        "Archive files or a whole folder on this device."
                    } else {
                        "Open an archive and extract the entries you select."
                    },
                    step = step,
                )
            }
            if (compress) {
                if (!hasInput) {
                    item("pick") {
                        FilePickCard(
                            title = "Choose files",
                            hint = "Pick any files, or a whole folder",
                            icon = EzIcons.Archive,
                            tint = EzColors.Primary,
                            onClick = onPickFiles,
                        )
                    }
                    item("pick-folder") { EzTextButton(text = "Pick a folder instead", onClick = onPickFolder) }
                } else {
                    compressSources(state, onEvent, onPickFiles, onPickFolder)
                    outputName(state, onEvent)
                }
            } else {
                if (state.archive == null) {
                    item("pick") {
                        FilePickCard(
                            title = "Choose an archive",
                            hint = "Choose a ZIP, TAR, TAR.GZ, or 7z file",
                            icon = EzIcons.Folder,
                            tint = EzColors.Primary,
                            onClick = onPickArchive,
                        )
                    }
                } else {
                    item("archive") {
                        PickedFileCard(
                            name = state.archiveName.ifEmpty { "Archive" },
                            meta = if (state.listing) "Reading archive" else countOf(state.entries.size, "entry", "entries"),
                            thumbnail = null,
                            onRemove = null,
                        )
                    }
                    extractEntries(state, state.entries, onEvent)
                }
            }
            if (hasInput) destinationCard(state, onPickDestination)
            archiveError(state.error)
        }
    }
}

@Composable
private fun ArchiveBottomBar(state: ArchiveUiState, onEvent: (ArchiveEvent) -> Unit) {
    val compress = state.mode == ArchiveMode.Compress
    val label = when {
        state.running -> "Working"
        compress && state.sourceFolder != null -> "Create archive · folder"
        compress && state.sources.isNotEmpty() -> "Create archive · ${countOf(state.sources.size, "file")}"
        compress -> "Create archive"
        state.selected.isNotEmpty() -> "Extract · ${countOf(state.selected.size, "entry", "entries")}"
        else -> "Extract"
    }
    Column(Modifier.fillMaxWidth()) {
        if (state.running) EzTextButton(text = "Cancel", onClick = { onEvent(ArchiveEvent.Cancel) }, modifier = Modifier.fillMaxWidth())
        ToolActionBar(
            label = label,
            enabled = state.canStart,
            onClick = { onEvent(ArchiveEvent.Run) },
            icon = if (compress) EzIcons.Archive else EzIcons.Folder,
            running = state.running,
            progress = progressFraction(state.progress),
        )
    }
}

private fun countOf(n: Int, noun: String, plural: String = "${noun}s"): String = if (n == 1) "1 $noun" else "$n $plural"


private fun progressFraction(p: ZipJobProgress?): Float? {
    if (p == null) return null
    if (p.entriesTotal == 0) return null
    return (p.entriesDone.toFloat() / p.entriesTotal).coerceIn(0f, 1f)
}
