package com.xfiles.feature.tools

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.ArchiveHandoff
import com.xfiles.core.documents.ZipJobProgress
import com.xfiles.core.documents.ZipEntryInfo
import com.xfiles.feature.tools.domain.CompressToZipUseCase
import com.xfiles.feature.tools.domain.ExtractArchiveUseCase
import com.xfiles.feature.tools.domain.ListZipEntriesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

enum class ArchiveMode { Compress, Extract }

/** Immutable screen state. Lists and progress only change through [ArchiveToolViewModel.onEvent]. */
data class ArchiveUiState(
    val mode: ArchiveMode = ArchiveMode.Compress,
    val sources: List<Uri> = emptyList(),
    val sourceFolder: Uri? = null,
    val outputName: String = "Archive.zip",
    val archive: Uri? = null,
    val archiveName: String = "",
    val entries: List<ZipEntryInfo> = emptyList(),
    val selected: Set<String> = emptySet(),
    val destination: Uri? = null,
    val running: Boolean = false,
    val progress: ZipJobProgress? = null,
    val resultMessage: String? = null,
    val error: String? = null,
) {
    val canStart: Boolean
        get() = !running && destination != null && when (mode) {
            ArchiveMode.Compress -> sources.isNotEmpty() || sourceFolder != null
            ArchiveMode.Extract -> archive != null && (selected.isNotEmpty() || entries.isEmpty())
        }
}

sealed interface ArchiveEvent {
    data class Start(val mode: ArchiveMode) : ArchiveEvent
    data class PickSources(val files: List<Uri>) : ArchiveEvent
    data class PickSourceFolder(val folder: Uri?) : ArchiveEvent
    data class PickArchive(val archive: Uri, val name: String) : ArchiveEvent
    data class PickDestination(val folder: Uri) : ArchiveEvent
    data class ToggleEntry(val name: String) : ArchiveEvent
    data class SelectAll(val on: Boolean) : ArchiveEvent
    data class SetOutputName(val name: String) : ArchiveEvent
    data object Run : ArchiveEvent
    data object Cancel : ArchiveEvent
    data object DismissResult : ArchiveEvent
}

@HiltViewModel
class ArchiveToolViewModel @Inject constructor(
    private val compress: CompressToZipUseCase,
    private val extract: ExtractArchiveUseCase,
    private val listEntries: ListZipEntriesUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun onEvent(event: ArchiveEvent) {
        when (event) {
            is ArchiveEvent.Start -> {
                mutableState.update { ArchiveUiState(mode = event.mode) }
                takeHandoff(event.mode)
            }
            is ArchiveEvent.PickSources -> mutableState.update { it.copy(sources = event.files, sourceFolder = null) }
            is ArchiveEvent.PickSourceFolder -> mutableState.update { it.copy(sourceFolder = event.folder, sources = emptyList()) }
            is ArchiveEvent.PickArchive -> pickArchive(event.archive, event.name)
            is ArchiveEvent.PickDestination -> mutableState.update { it.copy(destination = event.folder) }
            is ArchiveEvent.ToggleEntry -> mutableState.update {
                val next = if (event.name in it.selected) it.selected - event.name else it.selected + event.name
                it.copy(selected = next)
            }
            is ArchiveEvent.SelectAll -> mutableState.update {
                it.copy(selected = if (event.on) it.entries.filter { e -> !e.isDir }.map { e -> e.name }.toSet() else emptySet())
            }
            is ArchiveEvent.SetOutputName -> mutableState.update { it.copy(outputName = event.name) }
            ArchiveEvent.Run -> run()
            ArchiveEvent.Cancel -> job?.cancel()
            ArchiveEvent.DismissResult -> mutableState.update { it.copy(resultMessage = null, error = null) }
        }
    }

    /** A file staged by the library's menu (Compress to ZIP / Extract) is taken once, when the tool opens. */
    private fun takeHandoff(mode: ArchiveMode) {
        when (mode) {
            ArchiveMode.Compress -> ArchiveHandoff.takeSources().takeIf { it.isNotEmpty() }
                ?.let { onEvent(ArchiveEvent.PickSources(it)) }
            ArchiveMode.Extract -> ArchiveHandoff.takeArchive()
                ?.let { (uri, name) -> onEvent(ArchiveEvent.PickArchive(uri, name)) }
        }
    }

    private fun pickArchive(archive: Uri, name: String) {
        mutableState.update { it.copy(archive = archive, archiveName = name, entries = emptyList(), selected = emptySet(), error = null) }
        viewModelScope.launch {
            runCatching { listEntries(archive) }
                .onSuccess { list ->
                    mutableState.update { it.copy(entries = list, selected = list.filter { e -> !e.isDir }.map { e -> e.name }.toSet()) }
                }
                .onFailure { e -> mutableState.update { it.copy(error = "This file cannot be opened: ${e.message.orEmpty()}") } }
        }
    }

    private fun run() {
        val s = mutableState.value
        if (!s.canStart) return
        mutableState.update { it.copy(running = true, progress = null, resultMessage = null, error = null) }
        job = viewModelScope.launch {
            try {
                val dest = s.destination!!
                val message = when (s.mode) {
                    ArchiveMode.Compress -> {
                        val sources = s.sourceFolder?.let { listOf(it) } ?: s.sources
                        compress(sources, dest, s.outputName) { p -> mutableState.update { it.copy(progress = p) } }
                        "Saved ${s.outputName.ensureZip()}"
                    }
                    ArchiveMode.Extract -> {
                        val only = if (s.entries.isNotEmpty() && s.selected.size < s.entries.count { !it.isDir }) s.selected else null
                        val count = extract(s.archive!!, dest, only) { p -> mutableState.update { it.copy(progress = p) } }
                        "Extracted $count file${if (count == 1) "" else "s"}"
                    }
                }
                mutableState.update { it.copy(running = false, resultMessage = message) }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(running = false, error = "Cancelled") }
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(running = false, error = e.message ?: "The archive could not be made") }
            }
        }
    }

    private fun String.ensureZip() = if (endsWith(".zip", ignoreCase = true)) this else "$this.zip"
}
