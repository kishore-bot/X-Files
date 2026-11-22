package com.xfiles.feature.reader.domain

import android.graphics.Bitmap
import android.util.Log
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfPageText
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Index of the page that shares a spread with [page] (pairs 0-1, 2-3...), or null when there is none. */
internal fun pdfPartner(page: Int, count: Int): Int? =
    (if (page % 2 == 0) page + 1 else page - 1).takeIf { it in 0 until count }

/**
 * Renders PDF pages for the reader: a small bounded cache of recent pages, neighbour prefetch, grid
 * thumbnails and text. The cache is main-thread only, so one instance serves one reader view model.
 */
class PdfPagesUseCase @Inject constructor(
    private val documents: DocumentRepository,
) {
    private val cache = object : LinkedHashMap<Int, FilePreview.Pdf>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FilePreview.Pdf>?): Boolean =
            size > CACHED_PAGES
    }
    private var prefetchJob: Job? = null

    /** Keeps a page that was just opened, so turning back to it is instant. */
    fun remember(page: FilePreview.Pdf) {
        cache[page.page] = page
    }

    /** Page bitmap from the small cache, or rendered once and kept while recent. */
    suspend fun page(file: FileEntry, index: Int, password: String?): FilePreview.Pdf {
        cache[index]?.let { return it }
        return documents.page(file, index, password).also { cache[index] = it }
    }

    /** Warms the neighbours of what is shown, so the next turn is already rendered. Failures are silent. */
    fun prefetch(scope: CoroutineScope, file: FileEntry, shown: Int, count: Int, twoPage: Boolean, password: String?) {
        prefetchJob?.cancel()
        val targets = if (twoPage) {
            val start = shown - shown % 2
            listOf(start + 2, start + 3, start - 2, start - 1)
        } else {
            listOf(shown + 1, shown - 1)
        }
        prefetchJob = scope.launch {
            for (index in targets) {
                if (index !in 0 until count || cache.containsKey(index)) continue
                try {
                    page(file, index, password)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("XfilesReader", "PDF prefetch skipped", error)
                }
            }
        }
    }

    fun clear() {
        prefetchJob?.cancel()
        prefetchJob = null
        cache.clear()
    }

    /** A page for the grid: small, and read off the main thread. Null when none can be read. */
    suspend fun thumbnail(file: FileEntry, index: Int, password: String?): Bitmap? =
        try {
            documents.pageThumbnail(file, index, password, GRID_THUMBNAIL_EDGE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("XfilesReader", "PDF thumbnail failed", error)
            null
        }

    /** Character geometry of page [index] for text selection or bookmarks; null when it cannot be read. */
    suspend fun text(file: FileEntry, index: Int, password: String?): PdfPageText? =
        try {
            documents.pageText(file, index, password)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("XfilesReader", "PDF text could not be read", error)
            null
        }

    private companion object {
        /** Enough for the pages on screen in the continuous view, with a neighbour each side. */
        const val CACHED_PAGES = 10
        const val GRID_THUMBNAIL_EDGE = 240
    }
}
