package com.xfiles.feature.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.IncompleteOfficeCopyException
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.OfficeEditRepository
import com.xfiles.documentengine.OfficeEditException
import com.xfiles.documentengine.OfficeEditSession
import com.xfiles.documentengine.OfficeFindQuery
import com.xfiles.documentengine.OfficeSearchState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the panel's find text currently matches in the working copy. */
sealed interface OfficeMatches {
    /** Nothing typed yet, or not searched since the last change. */
    data object Idle : OfficeMatches

    /** The index is being built; [scannedParts] of [totalParts] parts are done. */
    data class Searching(val scannedParts: Int = 0, val totalParts: Int = 0) : OfficeMatches

    /** [index] is 0-based; [snippet] is the text around that match. [truncated] means [total] is a lower bound. */
    data class Found(val total: Int, val index: Int, val snippet: String, val truncated: Boolean = false) : OfficeMatches

    data object NoMatches : OfficeMatches
}

data class OfficeFindForm(
    val find: String = "",
    val replacement: String = "",
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val includeNumberCells: Boolean = false,
) {
    fun query(): OfficeFindQuery? = find.takeIf { it.isNotEmpty() }?.let {
        OfficeFindQuery(it, matchCase, wholeWord, includeNumberCells)
    }
}

/** The panel's state. [Preparing] while the document is copied into the workspace. */
sealed interface OfficeEditUiState {
    data object Preparing : OfficeEditUiState

    data class Failed(val message: String) : OfficeEditUiState

    data class Ready(
        val form: OfficeFindForm = OfficeFindForm(),
        val matches: OfficeMatches = OfficeMatches.Idle,
        val busy: Boolean = false,
        val message: String? = null,
        /** True when the working copy holds edits that the original does not. */
        val edited: Boolean = false,
    ) : OfficeEditUiState
}

/**
 * Drives the find and replace panel for one document. Edits accumulate: each
 * replacement runs on the latest working copy, and the original file is never
 * written. The match index lives in the native session: Next and Prev move an
 * index and fetch one snippet, and nothing rescans. Typing is debounced, and a
 * newer search cancels an older one.
 */
@HiltViewModel
class OfficeFindReplaceViewModel @Inject constructor(
    private val repository: OfficeEditRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<OfficeEditUiState>(OfficeEditUiState.Preparing)
    val state: StateFlow<OfficeEditUiState> = mutableState.asStateFlow()

    private val previewChannel = Channel<FilePreview.Structured>(Channel.CONFLATED)
    /** Refreshed previews of the working copy, after every applied replacement. */
    val previews: Flow<FilePreview.Structured> = previewChannel.receiveAsFlow()

    private var uri: Uri? = null
    private var name: String = ""
    private var source: File? = null
    private var working: File? = null
    private var session: OfficeEditSession? = null
    private var total = 0
    private var index = 0
    private var truncated = false
    private var searchJob: Job? = null
    private var navJob: Job? = null
    private var editJob: Job? = null
    private var bindJob: Job? = null
    /** Bumped by every [bind]; a staging that finishes under an older value belongs to a document no longer shown. */
    private var bindGeneration = 0

    /** Stages [uri] for editing. Calling it again with the same document does nothing. */
    fun bind(uri: Uri, name: String) {
        if (this.uri == uri && mutableState.value !is OfficeEditUiState.Failed) return
        val previous = listOfNotNull(working, source).distinct()
        val oldSession = session
        this.uri = uri
        this.name = name
        source = null
        working = null
        session = null
        total = 0
        index = 0
        searchJob?.cancel()
        navJob?.cancel()
        editJob?.cancel()
        bindJob?.cancel()
        val generation = ++bindGeneration
        mutableState.value = OfficeEditUiState.Preparing
        bindJob = viewModelScope.launch {
            oldSession?.let { repository.close(it) }
            repository.discardAll(previous)
            var staged: File? = null
            var opened: OfficeEditSession? = null
            try {
                val copy = repository.stage(uri, name).also { staged = it }
                val handle = repository.open(copy).also { opened = it }
                // A newer bind owns the panel now; this document's copy is released below.
                if (generation != bindGeneration) return@launch
                source = copy
                working = copy
                session = handle
                staged = null
                opened = null
                mutableState.value = OfficeEditUiState.Ready()
            } catch (error: OfficeEditException) {
                if (generation == bindGeneration) mutableState.value = OfficeEditUiState.Failed(error.message ?: UNREADABLE)
            } catch (error: IllegalArgumentException) {
                if (generation == bindGeneration) mutableState.value = OfficeEditUiState.Failed(UNSUPPORTED)
            } finally {
                withContext(NonCancellable) {
                    opened?.let { repository.close(it) }
                    staged?.let { repository.discardAll(listOf(it)) }
                }
            }
        }
    }

    fun onFind(text: String) {
        updateReady { it.copy(form = it.form.copy(find = text), matches = OfficeMatches.Idle, message = null) }
        scheduleSearch(focus = 0, debounceMs = TYPING_DEBOUNCE_MS)
    }

    fun onReplacement(text: String) = updateReady { it.copy(form = it.form.copy(replacement = text)) }

    fun onMatchCase(enabled: Boolean) {
        updateReady { it.copy(form = it.form.copy(matchCase = enabled), message = null) }
        scheduleSearch(focus = 0, debounceMs = 0)
    }

    fun onWholeWord(enabled: Boolean) {
        updateReady { it.copy(form = it.form.copy(wholeWord = enabled), message = null) }
        scheduleSearch(focus = 0, debounceMs = 0)
    }

    fun onIncludeNumberCells(enabled: Boolean) {
        updateReady { it.copy(form = it.form.copy(includeNumberCells = enabled), message = null) }
        scheduleSearch(focus = 0, debounceMs = 0)
    }

    /** Moves to the next match, wrapping at the end. */
    fun next() {
        if (total > 0) moveTo((index + 1) % total)
    }

    /** Moves to the previous match, wrapping at the start. */
    fun previous() {
        if (total > 0) moveTo((index - 1 + total) % total)
    }

    fun replaceCurrent() = applyEdit(onlyIndex = index)

    fun replaceAll() = applyEdit(onlyIndex = null)

    /** Writes the working copy to [destination], the location the user picked. */
    fun saveCopy(destination: Uri) {
        val file = working ?: return
        launchBusy {
            try {
                repository.saveCopy(file, destination)
                updateReady { it.copy(message = "Copy saved.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateReady { it.copy(message = (error as? IncompleteOfficeCopyException)?.message ?: "The copy could not be saved.") }
            }
        }
    }

    /** Drops all unsaved edits and returns the working copy to the original. */
    fun discardEdits() {
        val edited = working?.takeIf { it != source } ?: return
        val original = source ?: return
        val oldSession = session
        working = original
        session = null
        total = 0
        index = 0
        searchJob?.cancel()
        navJob?.cancel()
        editJob?.cancel()
        updateReady { it.copy(matches = OfficeMatches.Idle, busy = false, message = null, edited = false) }
        viewModelScope.launch {
            oldSession?.let { repository.close(it) }
            repository.discardAll(listOf(edited))
            session = repository.open(original)
            search(focus = 0)
        }
    }

    /** The name suggested for a saved copy, e.g. "Report (edited).docx". */
    fun suggestedCopyName(): String {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        return if (extension.isEmpty()) "$base (edited)" else "$base (edited).$extension"
    }

    override fun onCleared() {
        // Native handle release is cheap and not IO; the scope is already gone here.
        session?.let {
            it.cancel()
            it.close()
        }
        session = null
    }

    private fun moveTo(target: Int) {
        val current = session ?: return
        navJob?.cancel()
        navJob = viewModelScope.launch {
            val snippet = repository.snippet(current, target)
            index = target
            updateReady { it.copy(matches = OfficeMatches.Found(total, target, snippet, truncated)) }
        }
    }

    private fun scheduleSearch(focus: Int, debounceMs: Long) {
        searchJob?.cancel()
        navJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            search(focus)
        }
    }

    /** Builds the index for the current query and lands on [focus]. */
    private suspend fun search(focus: Int) {
        val current = session ?: return
        val query = (mutableState.value as? OfficeEditUiState.Ready)?.form?.query()
        if (query == null) {
            total = 0
            index = 0
            updateReady { it.copy(matches = OfficeMatches.Idle) }
            return
        }
        updateReady { it.copy(matches = OfficeMatches.Searching()) }
        try {
            var last: OfficeSearchState? = null
            repository.index(current, query).collect { step ->
                last = step
                updateReady { it.copy(matches = OfficeMatches.Searching(step.scannedParts, step.totalParts)) }
            }
            val done = last ?: return
            total = done.total
            truncated = done.truncated
            index = if (total == 0) 0 else focus.coerceIn(0, total - 1)
            val matches = if (total == 0) {
                OfficeMatches.NoMatches
            } else {
                OfficeMatches.Found(total, index, repository.snippet(current, index), truncated)
            }
            updateReady { it.copy(matches = matches) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            total = 0
            index = 0
            updateReady { it.copy(matches = OfficeMatches.Idle, message = error.message ?: UNREADABLE) }
        }
    }

    private fun applyEdit(onlyIndex: Int?) {
        val file = working ?: return
        val current = session ?: return
        val form = (mutableState.value as? OfficeEditUiState.Ready)?.form ?: return
        val query = form.query() ?: return
        if (editJob?.isActive == true) return
        editJob = launchBusy {
            try {
                val edit = repository.replace(current, name, query, form.replacement, onlyIndex)
                val produced = edit.file
                if (produced == null) {
                    updateReady { it.copy(message = "No matches to replace.") }
                } else {
                    val previous = working
                    working = produced
                    if (previous != null && previous != source) repository.discardAll(listOf(previous))
                    val count = edit.report.replaced
                    previewChannel.trySend(repository.preview(produced, name))
                    updateReady {
                        it.copy(
                            edited = true,
                            message = if (onlyIndex == null) "Replaced $count." else "Replaced this match.",
                        )
                    }
                }
                // Replace-one keeps the index (only the edited part was rescanned), so this is O(1).
                // Replace-all dropped it, so this rebuilds it.
                search(focus = index)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateReady { it.copy(message = error.message ?: "The replacement could not be made.") }
            }
        }
    }

    private fun launchBusy(block: suspend () -> Unit): Job {
        updateReady { it.copy(busy = true) }
        return viewModelScope.launch {
            try {
                block()
            } finally {
                updateReady { it.copy(busy = false) }
            }
        }
    }

    private inline fun updateReady(transform: (OfficeEditUiState.Ready) -> OfficeEditUiState.Ready) {
        mutableState.update { current -> if (current is OfficeEditUiState.Ready) transform(current) else current }
    }

    private companion object {
        const val TYPING_DEBOUNCE_MS = 180L
        const val UNREADABLE = "The document could not be opened."
        const val UNSUPPORTED = "Find and replace supports DOCX, PPTX, ODT, ODP and ODS."
    }
}
