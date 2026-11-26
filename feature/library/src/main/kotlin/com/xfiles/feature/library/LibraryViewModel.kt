package com.xfiles.feature.library

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.ConversionRepository
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FolderLibrary
import com.xfiles.core.documents.FolderRepository
import com.xfiles.core.documents.LibraryContentSearch
import com.xfiles.core.documents.LibrarySearchRepository
import com.xfiles.core.documents.PrintRequest
import com.xfiles.core.documents.canPrint
import com.xfiles.documents.library.LibraryCategory
import com.xfiles.documents.library.libraryCategories
import com.xfiles.documents.search.FileNameIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the library lists for the current query: [recents] matches (the opening
 * history, ranked by name), and [files] matches from the chosen folders, also
 * narrowed to the selected category. Both are best match first, or in their
 * original order for a blank query.
 */
data class LibraryListing(
    val recents: List<FileEntry> = emptyList(),
    val files: List<FileEntry> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val progress: com.xfiles.core.documents.FileProgress,
    private val folders: FolderRepository,
    private val search: LibrarySearchRepository,
    private val conversions: ConversionRepository,
) : ViewModel() {
    val recents = documents.recents
    val lastImage = progress.lastImage
    private val printRequests = Channel<PrintRequest>(Channel.BUFFERED)
    /** Print previews ready for the system print screen, or refusals to show; collected while the library is shown. */
    val prints = printRequests.receiveAsFlow()
    private var printJob: Job? = null
    private val mutableRecentsLoaded = MutableStateFlow(false)
    /** True once the first recents read finished; the launch screen reports fully drawn on it. */
    val recentsLoaded = mutableRecentsLoaded.asStateFlow()
    val bookmarks = documents.bookmarks
    private val mutableLibrary = MutableStateFlow(FolderLibrary())
    val library = mutableLibrary.asStateFlow()
    private val mutableContentSearch = MutableStateFlow(LibraryContentSearch())
    val contentSearch = mutableContentSearch.asStateFlow()
    private val mutableSearchEnabled = MutableStateFlow(false)
    val searchEnabled = mutableSearchEnabled.asStateFlow()
    private var scan: Job? = null
    private var resumedOnce = false
    private var refreshAfterScan = false
    private var queryJob: Job? = null
    private var indexJob: Job? = null
    private var indexingSnapshot: FolderLibrary? = null
    private var clearIndexJob: Job? = null

    private val query = MutableStateFlow("")
    private val mutableCategory = MutableStateFlow(LibraryCategory.entries.firstOrNull { it.name == progress.read("library", "category") } ?: LibraryCategory.All)
    /** The selected filter chip. */
    val category = mutableCategory.asStateFlow()

    /** Names indexed once per list change (on a background dispatcher), not once per keystroke. */
    private val fileCorpus: Flow<Corpus> = corpus(library.map { it.files })
    private val recentCorpus: Flow<Corpus> = corpus(documents.recents)

    /** The lists for the query and filter; ranked off the main thread so big libraries stay smooth. */
    val listing: StateFlow<LibraryListing> = combine(query, mutableCategory, fileCorpus, recentCorpus) { q, c, files, recent ->
        LibraryListing(
            recents = recent.matching(q, LibraryCategory.All),
            files = files.matching(q, c),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LibraryListing())

    init {
        viewModelScope.launch {
            documents.loadRecents()
            mutableRecentsLoaded.value = true
        }
        refresh()
    }

    fun refresh() = updateFolders()
    fun refreshOnReturn() {
        if (scan?.isActive != true) refresh()
        else if (resumedOnce) refreshAfterScan = true
        resumedOnce = true
    }
    fun addFolder(uri: Uri, replacing: Uri? = null) = updateFolders { folders.link(uri, replacing) }
    fun removeFolder(uri: Uri) = updateFolders { folders.unlink(uri) }

    fun clearRecents() {
        viewModelScope.launch { documents.clearRecents() }
    }

    fun removeRecent(file: FileEntry) {
        viewModelScope.launch { documents.removeRecent(file) }
    }

    /** Lays [file] out as a plain PDF off the main thread, then sends it to [prints]. */
    fun print(file: FileEntry) {
        if (!file.kind.canPrint() || printJob?.isActive == true) return
        printJob = viewModelScope.launch {
            val request = try {
                PrintRequest.Ready(conversions.printable(file, password = null), file.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PrintRequest.Failed("This file can't be printed. Open it first, then try again.")
            }
            printRequests.send(request)
        }
    }

    fun setQuery(value: String) {
        query.value = value.trim()
    }

    fun setCategory(value: LibraryCategory) {
        mutableCategory.value = value
        progress.write("library", "category", value.name)
    }

    /** Files shared into Xfiles beyond the first one: they go to Recent, so they can be opened from the library. */
    fun receiveShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    documents.remember(documents.describe(uri))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Not readable any more: skip it.
                }
            }
        }
    }

    fun setSearchEnabled(enabled: Boolean) {
        mutableSearchEnabled.value = enabled
        if (enabled) {
            if (!library.value.scanning) refreshSearchIndex(library.value)
            return
        }
        stopSearch()
        indexJob?.cancel()
        clearIndexJob = viewModelScope.launch { clearSearchIndex() }
    }

    fun searchInside(query: String) {
        stopSearch()
        val snapshot = library.value
        if (!searchEnabled.value || query.length < 2) return
        queryJob = viewModelScope.launch {
            delay(240)
            try {
                search.search(query, snapshot.files) { result -> mutableContentSearch.value = result }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesLibrary", "File content search failed", error)
                mutableContentSearch.value = LibraryContentSearch(query = query)
            } catch (error: LinkageError) {
                Log.e("XfilesLibrary", "Rust search unavailable", error)
                mutableContentSearch.value = LibraryContentSearch(query = query)
            }
        }
    }

    fun stopSearch() {
        queryJob?.cancel()
        queryJob = null
        mutableContentSearch.value = LibraryContentSearch()
    }

    private suspend fun clearSearchIndex() {
        try {
            search.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("XfilesLibrary", "Could not clear content search index", error)
        } catch (error: LinkageError) {
            Log.e("XfilesLibrary", "Rust search unavailable", error)
        }
    }

    private fun refreshSearchIndex(snapshot: FolderLibrary) {
        if (!searchEnabled.value || (indexJob?.isActive == true && indexingSnapshot == snapshot)) return
        indexJob?.cancel()
        indexingSnapshot = snapshot
        indexJob = viewModelScope.launch {
            clearIndexJob?.join()
            try {
                search.refresh(snapshot.files, prune = snapshot.error == null && !snapshot.reachedLimit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesLibrary", "File content indexing failed", error)
            } catch (error: LinkageError) {
                Log.e("XfilesLibrary", "Rust search unavailable", error)
            }
        }
    }

    private fun updateFolders(change: suspend () -> Unit = {}) {
        scan?.cancel()
        scan = viewModelScope.launch {
            mutableLibrary.value = mutableLibrary.value.copy(scanning = true, error = null)
            mutableLibrary.value = try {
                change()
                folders.scan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableLibrary.value.copy(scanning = false, error = "The folder could not be updated. Try selecting it again.")
            }
            refreshSearchIndex(library.value)
            documents.loadRecents()
            if (refreshAfterScan) {
                refreshAfterScan = false
                refresh()
            }
        }
    }

    private class Corpus(val entries: List<FileEntry>, val index: FileNameIndex, val categories: List<Set<LibraryCategory>>) {
        fun matching(query: String, category: LibraryCategory): List<FileEntry> {
            val order = index.search(query)
            if (category == LibraryCategory.All) return order.map { entries[it] }
            return order.filter { category in categories[it] }.map { entries[it] }
        }
    }

    private fun corpus(source: Flow<List<FileEntry>>): Flow<Corpus> = source
        .distinctUntilChanged()
        .map { entries ->
            Corpus(
                entries = entries,
                index = FileNameIndex(entries.map { it.name }),
                categories = entries.map { libraryCategories(it.name) },
            )
        }
}
