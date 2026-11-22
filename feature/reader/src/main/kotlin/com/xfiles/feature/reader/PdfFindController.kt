package com.xfiles.feature.reader

import android.util.Log
import com.xfiles.core.documents.FileEntry
import com.xfiles.feature.reader.domain.PdfSearchUseCase
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The find bar's search over the open PDF: the matches, which one is selected, and moving between them. */
class PdfFindController @Inject constructor(
    private val search: PdfSearchUseCase,
) {
    private val mutable = MutableStateFlow(PdfFindState())
    val state: StateFlow<PdfFindState> = mutable.asStateFlow()
    private var job: Job? = null

    /** Searches [file] for [query]; [onFirstMatch] turns the reader to the first page found. */
    fun start(scope: CoroutineScope, file: FileEntry, query: String, password: String?, onFirstMatch: (Int) -> Unit) {
        clear()
        mutable.value = PdfFindState(loading = true)
        job = scope.launch {
            delay(SEARCH_DELAY_MS)
            try {
                val found = search(file, query, password)
                mutable.value = found
                found.matches.firstOrNull()?.let { onFirstMatch(it.page) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesReader", "PDF search failed", error)
                mutable.value = PdfFindState(error = true)
            }
        }
    }

    /** Selects the match [direction] steps away and turns to its page through [onPage]. */
    fun move(direction: Int, onPage: (Int) -> Unit) {
        val current = mutable.value
        val index = current.selected + direction
        val match = current.matches.getOrNull(index) ?: return
        mutable.value = current.copy(selected = index)
        onPage(match.page)
    }

    fun clear() {
        job?.cancel()
        job = null
        mutable.value = PdfFindState()
    }

    private companion object {
        const val SEARCH_DELAY_MS = 180L
    }
}
