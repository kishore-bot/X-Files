package com.xfiles.feature.tools

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.ArchiveHandoff
import com.xfiles.core.documents.ArchiveOutputFormat
import com.xfiles.core.documents.ZipJobProgress
import com.xfiles.core.documents.ZipEntryInfo
import com.xfiles.feature.tools.domain.CreateArchiveUseCase
import com.xfiles.feature.tools.domain.ExtractArchiveUseCase
import com.xfiles.feature.tools.domain.ListArchiveEntriesUseCase
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
    val outputName: String = "Archive",
    val format: ArchiveOutputFormat = ArchiveOutputFormat.Zip,
    val passwordProtected: Boolean = false,
    val password: String = "",
    val listing: Boolean = false,
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
    val needsPassword: Boolean
        get() = if (mode == ArchiveMode.Compress) passwordProtected else entries.any { it.encrypted && it.name in selected }

    val canStart: Boolean
        get() = !running && !listing && destination != null && (!needsPassword || password.isNotEmpty()) && when (mode) {
            ArchiveMode.Compress -> (sources.isNotEmpty() || sourceFolder != null) && outputName.none { it in "/\\:\u0000" }
            ArchiveMode.Extract -> archive != null && selected.isNotEmpty()
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
    data class SetFormat(val format: ArchiveOutputFormat) : ArchiveEvent
    data class SetProtected(val protected: Boolean) : ArchiveEvent
    data class SetPassword(val password: String) : ArchiveEvent
    data object Run : ArchiveEvent
    data object Cancel : ArchiveEvent
    data object DismissResult : ArchiveEvent
}

@HiltViewModel
class ArchiveToolViewModel @Inject constructor(
    private val compress: CreateArchiveUseCase,
    private val extract: ExtractArchiveUseCase,
    private val listEntries: ListArchiveEntriesUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var listing: Job? = null

    fun onEvent(event: ArchiveEvent) {
        when (event) {
            is ArchiveEvent.Start -> {
                listing?.cancel()
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
                it.copy(selected = if (event.on) it.entries.map { e -> e.name }.toSet() else emptySet())
            }
            is ArchiveEvent.SetOutputName -> mutableState.update { it.copy(outputName = event.name) }
            is ArchiveEvent.SetFormat -> mutableState.update { it.copy(format = event.format, passwordProtected = false, password = "") }
            is ArchiveEvent.SetProtected -> mutableState.update { it.copy(passwordProtected = event.protected, password = "") }
            is ArchiveEvent.SetPassword -> mutableState.update { it.copy(password = event.password) }
            ArchiveEvent.Run -> run()
            ArchiveEvent.Cancel -> job?.cancel()
            ArchiveEvent.DismissResult -> mutableState.update { it.copy(resultMessage = null, error = null) }
        }
    }

    /** A file staged by the library's menu (Create archive / Extract) is taken once, when the tool opens. */
    private fun takeHandoff(mode: ArchiveMode) {
        when (mode) {
            ArchiveMode.Compress -> ArchiveHandoff.takeSources().takeIf { it.isNotEmpty() }
                ?.let { onEvent(ArchiveEvent.PickSources(it)) }
            ArchiveMode.Extract -> ArchiveHandoff.takeArchive()
                ?.let { (uri, name) -> onEvent(ArchiveEvent.PickArchive(uri, name)) }
        }
    }

    private fun pickArchive(archive: Uri, name: String) {
        listing?.cancel()
        mutableState.update { it.copy(archive = archive, archiveName = name, entries = emptyList(), selected = emptySet(), listing = true, password = "", error = null) }
        listing = viewModelScope.launch {
            try {
                val list = listEntries(archive)
                mutableState.update { it.copy(entries = list, selected = list.map { entry -> entry.name }.toSet(), listing = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(listing = false, error = failure.message ?: "This archive could not be opened") }
            }
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
                        val name = s.format.fileName(s.outputName)
                        compress(sources, dest, name, s.format, s.password.takeIf { s.needsPassword }) { p ->
                            mutableState.update { it.copy(progress = p) }
                        }
                        "Saved $name"
                    }
                    ArchiveMode.Extract -> {
                        val count = extract(s.archive!!, dest, s.selected, s.password.takeIf { s.needsPassword }) { p -> mutableState.update { it.copy(progress = p) } }
                        "Extracted $count file${if (count == 1) "" else "s"}"
                    }
                }
                mutableState.update { it.copy(running = false, resultMessage = message, password = "") }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(running = false, error = "Cancelled", password = "") }
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(running = false, error = e.message ?: "The archive could not be made") }
            }
        }
    }

}
