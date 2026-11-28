package com.xfiles.feature.reader

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.ArchiveError
import com.xfiles.core.documents.ArchiveException
import com.xfiles.core.documents.ArchiveProgress
import com.xfiles.core.documents.ArchiveRepository
import com.xfiles.documents.archive.ArchiveNode
import com.xfiles.documents.archive.ArchiveTree
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One path segment of the current folder, with the path that navigates to it. */
data class ArchiveCrumb(val name: String, val path: String)

/** What the browser shows. [rows] are the current folder's children, already sorted. */
data class ArchiveBrowse(
    val folderPath: String,
    val crumbs: List<ArchiveCrumb>,
    val rows: List<ArchiveNode>,
    val query: String,
    val hits: List<ArchiveNode>,
    val entryCount: Int,
)

sealed interface ArchiveUiState {
    data object Loading : ArchiveUiState
    data class Ready(val browse: ArchiveBrowse) : ArchiveUiState
    data class Failed(val message: String) : ArchiveUiState
}

/** The one long-running operation the browser allows at a time, or the last outcome. */
sealed interface ArchiveActivity {
    data object Idle : ArchiveActivity
    data class Working(val label: String, val progress: ArchiveProgress?) : ArchiveActivity
    data class Notice(val message: String) : ArchiveActivity
}

/** Asks for the archive password. [wrong] is true after the last password failed. */
data class PasswordPrompt(val wrong: Boolean)

/**
 * Browser state for one archive. Folder position and the search text live in
 * [SavedStateHandle], and the screen keeps this ViewModel while a nested file
 * is open, so backing out of an extracted entry returns to the same folder.
 *
 * The password, once it works, is kept in memory for this ViewModel only: it is
 * not saved state, is never written to disk, and is dropped when the reader
 * leaves the archive or a password is rejected.
 *
 * Index building and search run on Default; engine work runs on the
 * repository's IO lane; state is only written from viewModelScope (Main).
 */
@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val archives: ArchiveRepository,
    private val progress: com.xfiles.core.documents.FileProgress,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private var archive: Uri? = null
    private var tree: ArchiveTree? = null
    private var loadJob: Job? = null
    private var browseJob: Job? = null
    private var searchJob: Job? = null
    private var workJob: Job? = null

    /** Session-only; see the class comment. */
    private var sessionPassword: String? = null

    /** What to run once the user answers the password prompt. */
    private var pendingRetry: ((String) -> Unit)? = null

    private val mutableState = MutableStateFlow<ArchiveUiState>(ArchiveUiState.Loading)
    val state: StateFlow<ArchiveUiState> = mutableState.asStateFlow()

    private val mutableActivity = MutableStateFlow<ArchiveActivity>(ArchiveActivity.Idle)
    val activity: StateFlow<ArchiveActivity> = mutableActivity.asStateFlow()

    private val mutablePrompt = MutableStateFlow<PasswordPrompt?>(null)
    val prompt: StateFlow<PasswordPrompt?> = mutablePrompt.asStateFlow()

    private var folder: String
        get() = saved[KEY_FOLDER] ?: ""
        set(value) {
            saved[KEY_FOLDER] = value
            archive?.let { progress.write(it.toString(), "archive-folder", value) }
        }

    private var savedQuery: String
        get() = saved[KEY_QUERY] ?: ""
        set(value) {
            saved[KEY_QUERY] = value
        }

    /** Builds the tree once per archive. Re-opening the same archive keeps the existing tree. */
    fun open(uri: Uri) {
        if (archive == uri && tree != null) {
            browse()
            return
        }
        archive = uri
        saved[KEY_FOLDER] = progress.read(uri.toString(), "archive-folder").orEmpty()
        tree = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.value = ArchiveUiState.Loading
            try {
                val indexed = archives.index(uri)
                tree = indexed
                if (indexed.find(folder)?.isDirectory != true) folder = ""
                browse()
            } catch (failure: ArchiveException) {
                mutableState.value = ArchiveUiState.Failed(messageFor(failure.reason))
            }
        }
    }

    /** Moves into a folder. Unknown paths are ignored. */
    fun enter(path: String) {
        val current = tree ?: return
        if (current.find(path)?.isDirectory != true) return
        folder = path
        browse()
    }

    /** Pops one folder. Returns false at the root so the caller can leave the screen instead. */
    fun up(): Boolean {
        if (folder.isEmpty()) return false
        folder = folder.substringBeforeLast('/', missingDelimiterValue = "")
        browse()
        return true
    }

    fun setQuery(text: String) {
        savedQuery = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Short debounce so typing does not walk the whole tree on every key.
            if (text.isNotBlank()) delay(SEARCH_DEBOUNCE_MILLIS)
            browse()
        }
    }

    /**
     * Opens one file. Encrypted entries ask for the password first when none is
     * known for this session. [onReady] runs on Main with a `content://` URI.
     */
    fun openFile(path: String, encrypted: Boolean, onReady: (Uri) -> Unit) {
        if (busy()) return
        val retry: (String?) -> Unit = { password -> runOpen(path, password, onReady) }
        when {
            !encrypted -> retry(null)
            sessionPassword != null -> retry(sessionPassword)
            else -> askPassword(wrong = false) { retry(it) }
        }
    }

    /** Extracts every file under [folderPath] into the picked tree, in batches. */
    fun exportFolder(folderPath: String, treeUri: Uri) {
        if (archive == null || busy()) return
        val node = tree?.find(folderPath) ?: return
        runExport(node, treeUri, sessionPassword)
    }

    /** Answers the password prompt. Blank input is ignored; the prompt stays open. */
    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        val retry = pendingRetry ?: return
        pendingRetry = null
        mutablePrompt.value = null
        retry(password)
    }

    fun cancelPassword() {
        pendingRetry = null
        mutablePrompt.value = null
    }

    /** Stops a running extraction. The native side stops at its next check point. */
    fun cancelWork() {
        if (workJob?.isActive != true) return
        workJob?.cancel()
        mutableActivity.value = ArchiveActivity.Notice("Extraction cancelled.")
    }

    fun dismissNotice() {
        if (mutableActivity.value is ArchiveActivity.Notice) mutableActivity.value = ArchiveActivity.Idle
    }

    override fun onCleared() {
        super.onCleared()
        sessionPassword = null
        pendingRetry = null
        archives.releaseOpenCache()
    }

    private fun runOpen(path: String, password: String?, onReady: (Uri) -> Unit) {
        val source = archive ?: return
        workJob = viewModelScope.launch {
            mutableActivity.value = ArchiveActivity.Working(path.substringAfterLast('/'), null)
            try {
                val uri = archives.openEntry(source, path, password)
                if (password != null) sessionPassword = password
                mutableActivity.value = ArchiveActivity.Idle
                onReady(uri)
            } catch (failure: ArchiveException) {
                mutableActivity.value = ArchiveActivity.Idle
                onFailure(failure) { runOpen(path, it, onReady) }
            }
        }
    }

    private fun runExport(node: ArchiveNode, treeUri: Uri, password: String?) {
        val source = archive ?: return
        val label = node.name.ifEmpty { "archive" }
        workJob = viewModelScope.launch {
            mutableActivity.value = ArchiveActivity.Working("Extracting $label", null)
            try {
                val (paths, encrypted) = withContext(Dispatchers.Default) { collectEntries(node) }
                if (paths.isEmpty()) {
                    mutableActivity.value = ArchiveActivity.Notice("Nothing to extract here.")
                    return@launch
                }
                if (encrypted && password == null) {
                    mutableActivity.value = ArchiveActivity.Idle
                    askPassword(wrong = false) { runExport(node, treeUri, it) }
                    return@launch
                }
                paths.chunked(ArchiveRepository.MAX_BATCH_ENTRIES).forEach { batch ->
                    archives.exportToTree(source, batch, treeUri, password).collect { progress ->
                        if (progress.phase != ArchiveProgress.Phase.Done) {
                            mutableActivity.value = ArchiveActivity.Working("Extracting $label", progress)
                        }
                    }
                }
                if (password != null) sessionPassword = password
                mutableActivity.value = ArchiveActivity.Notice("Extracted ${paths.size} items to the folder you chose.")
            } catch (failure: ArchiveException) {
                if (failure.reason == ArchiveError.Cancelled) {
                    mutableActivity.value = ArchiveActivity.Notice(messageFor(ArchiveError.Cancelled))
                } else {
                    mutableActivity.value = ArchiveActivity.Idle
                    onFailure(failure) { runExport(node, treeUri, it) }
                }
            }
        }
    }

    /** Password failures reopen the prompt with the same action; anything else is a notice. */
    private fun onFailure(failure: ArchiveException, retry: (String) -> Unit) {
        when (failure.reason) {
            ArchiveError.PasswordRequired -> askPassword(wrong = false, retry = retry)
            ArchiveError.WrongPassword -> {
                sessionPassword = null
                askPassword(wrong = true, retry = retry)
            }
            else -> mutableActivity.value = ArchiveActivity.Notice(messageFor(failure.reason))
        }
    }

    private fun askPassword(wrong: Boolean, retry: (String) -> Unit) {
        pendingRetry = retry
        mutablePrompt.value = PasswordPrompt(wrong)
    }

    private fun busy(): Boolean {
        if (workJob?.isActive == true) {
            mutableActivity.value = ArchiveActivity.Notice("Wait for the current operation to finish.")
            return true
        }
        return false
    }

    private fun browse() {
        val current = tree ?: return
        val path = folder.takeIf { current.find(it) != null } ?: ""
        folder = path
        val currentQuery = savedQuery
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val browse = withContext(Dispatchers.Default) {
                val node = current.find(path) ?: current.root
                ArchiveBrowse(
                    folderPath = path,
                    crumbs = crumbsOf(path),
                    rows = node.sortedChildren,
                    query = currentQuery,
                    hits = if (currentQuery.isBlank()) emptyList() else current.search(currentQuery),
                    entryCount = current.entryCount,
                )
            }
            mutableState.value = ArchiveUiState.Ready(browse)
        }
    }

    /**
     * Depth-first walk of the archive records under [node] (including [node]).
     * Returns their paths and whether any of them is encrypted.
     */
    private fun collectEntries(node: ArchiveNode): Pair<List<String>, Boolean> {
        val paths = ArrayList<String>()
        var encrypted = false
        val stack = ArrayDeque<ArchiveNode>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            current.entry?.let {
                paths += it.path
                if (it.encrypted) encrypted = true
            }
            for (child in current.sortedChildren.asReversed()) stack.addLast(child)
        }
        return paths to encrypted
    }

    private fun crumbsOf(path: String): List<ArchiveCrumb> {
        if (path.isEmpty()) return emptyList()
        val crumbs = ArrayList<ArchiveCrumb>()
        var running = ""
        for (segment in path.split('/')) {
            running = if (running.isEmpty()) segment else "$running/$segment"
            crumbs += ArchiveCrumb(segment, running)
        }
        return crumbs
    }

    private companion object {
        const val KEY_FOLDER = "archive.folder"
        const val KEY_QUERY = "archive.query"
        const val SEARCH_DEBOUNCE_MILLIS = 150L

        fun messageFor(reason: ArchiveError): String = when (reason) {
            ArchiveError.Encrypted -> "This entry uses an encryption scheme Xfiles cannot open."
            ArchiveError.PasswordRequired -> "This entry is password-protected."
            ArchiveError.WrongPassword -> "That password does not open this archive."
            ArchiveError.TooLarge -> "This entry is too large for the archive browser on this device."
            ArchiveError.Unsafe -> "This entry has an unsafe path and was skipped."
            ArchiveError.Malformed -> "This archive is damaged or uses an unsupported layout."
            ArchiveError.Unsupported -> "This entry uses a compression method Xfiles cannot read."
            ArchiveError.Cancelled -> "Extraction cancelled."
            ArchiveError.Io -> "The archive could not be read or written. Check storage and try again."
        }
    }
}
