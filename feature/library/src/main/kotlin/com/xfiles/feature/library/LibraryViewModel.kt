package com.xfiles.feature.library

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FolderLibrary
import com.xfiles.core.documents.FolderRepository
import com.xfiles.core.documents.LibraryContentSearch
import com.xfiles.core.documents.LibrarySearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val folders: FolderRepository,
    private val search: LibrarySearchRepository,
) : ViewModel() {
    val recents = documents.recents
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
    private var queryJob: Job? = null

    init {
        viewModelScope.launch {
            documents.loadRecents()
            mutableRecentsLoaded.value = true
        }
        refresh()
    }

    fun refresh() = updateFolders()
    fun addFolder(uri: Uri) = updateFolders { folders.link(uri) }
    fun removeFolder(uri: Uri) = updateFolders { folders.unlink(uri) }

    fun clearRecents() {
        viewModelScope.launch { documents.clearRecents() }
    }

    fun setSearchEnabled(enabled: Boolean) {
        mutableSearchEnabled.value = enabled
        if (enabled) return
        stopSearch()
        viewModelScope.launch { clearSearchIndex() }
    }

    fun searchInside(query: String) {
        stopSearch()
        val snapshot = library.value
        if (!searchEnabled.value || snapshot.scanning || query.length < 2) return
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

    private fun updateFolders(change: suspend () -> Unit = {}) {
        stopSearch()
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
            documents.loadRecents()
        }
    }
}
