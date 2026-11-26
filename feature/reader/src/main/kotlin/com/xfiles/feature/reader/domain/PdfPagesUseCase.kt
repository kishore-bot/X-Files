package com.xfiles.feature.reader.domain

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Log
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfPageText
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val cache = object : LruCache<Int, FilePreview.Pdf>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(16L * MIB, 48L * MIB).toInt(),
    ) {
        override fun sizeOf(key: Int, value: FilePreview.Pdf): Int = value.bitmap.allocationByteCount
    }
    private data class Render(val result: Deferred<FilePreview.Pdf>, var readers: Int = 0)

    private val inFlight = mutableMapOf<Int, Render>()
    private var owner: CoroutineScope? = null
    private var uri: Uri? = null
    private var prefetchJob: Job? = null
    private var generation = 0L

    /** Seeds the reader session with the first rendered page. Used only by its ViewModel. */
    fun remember(file: FileEntry, page: FilePreview.Pdf, scope: CoroutineScope) {
        uri = file.uri
        owner = scope
        cache.put(page.page, page)
    }

    /** Simultaneous demand and prefetch for the same page share the render while any caller still needs it. */
    suspend fun page(file: FileEntry, index: Int, password: String?): FilePreview.Pdf {
        val scope = owner?.takeIf { uri == file.uri } ?: throw CancellationException("PDF session changed")
        cache.get(index)?.let { return it }
        val session = generation
        val render = inFlight.getOrPut(index) {
            Render(scope.async(start = CoroutineStart.LAZY) {
                val page = documents.page(file, index, password)
                try {
                    currentCoroutineContext().ensureActive()
                    if (generation != session) throw CancellationException("PDF session changed")
                } catch (cancelled: CancellationException) {
                    page.bitmap.recycle()
                    throw cancelled
                }
                cache.put(page.page, page)
                page
            })
        }
        render.readers++
        return try {
            render.result.await()
        } finally {
            render.readers--
            if (render.readers == 0 && inFlight[index] === render) {
                inFlight.remove(index)
                render.result.cancel()
            }
        }
    }

    /** Warms the neighbours of what is shown, so the next turn is already rendered. Failures are silent. */
    fun prefetch(scope: CoroutineScope, file: FileEntry, shown: Int, count: Int, twoPage: Boolean, password: String?) {
        prefetchJob?.cancel()
        val targets = if (twoPage) {
            val start = shown - shown % 2
            listOf(start + 2, start + 3)
        } else {
            listOf(shown + 1, shown - 1)
        }
        prefetchJob = scope.launch {
            for (index in targets) {
                if (index !in 0 until count || cache.get(index) != null) continue
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
        generation++
        owner = null
        uri = null
        inFlight.values.forEach { it.result.cancel() }
        inFlight.clear()
        trim()
    }

    fun trim() {
        prefetchJob?.cancel()
        prefetchJob = null
        // Visible composables can still hold these bitmaps. Drop only the cache's ownership.
        cache.evictAll()
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
        const val MIB = 1024 * 1024
        const val GRID_THUMBNAIL_EDGE = 240
    }
}
