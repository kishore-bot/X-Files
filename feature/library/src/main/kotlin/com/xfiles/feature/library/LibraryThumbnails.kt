package com.xfiles.feature.library

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.MemoryPressure
import com.xfiles.documents.DocumentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Small preview images for library rows: photos and videos from the content
 * provider's thumbnail, a PDF's first page rendered locally. Decoding runs on two
 * background threads, is bounded to the row size, is cancelled when the row
 * leaves the screen, and is kept in an 8 MiB memory cache. Nothing is copied to disk.
 */
internal object LibraryThumbnails {
    private const val CACHE_BYTES = 8 * 1024 * 1024
    private val decodeSlots = Dispatchers.IO.limitedParallelism(2)
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    init {
        // Previews are cheap to decode again, so under memory pressure they go first.
        MemoryPressure.register { cache.evictAll() }
    }

    /** Whether rows of this kind show a preview rather than the type icon. */
    fun supports(kind: DocumentKind): Boolean = kind == DocumentKind.Image || kind == DocumentKind.Video || kind == DocumentKind.Pdf

    /** A preview already decoded, so a recycled row can show it without waiting. */
    fun cached(file: FileEntry, side: Int): Bitmap? = cache.get(key(file, side))

    /**
     * The preview for [file], at most [side] pixels on its longest edge, or null
     * when none can be made (the caller keeps the type icon). Cancelling the caller
     * stops the decode.
     */
    suspend fun load(resolver: ContentResolver, file: FileEntry, side: Int): Bitmap? {
        val key = key(file, side)
        cache.get(key)?.let { return it }
        val signal = CancellationSignal()
        val bitmap = coroutineScope {
            // Leaving the screen cancels this scope; the watcher (off the decode threads) aborts the provider's decode.
            val watcher = launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    signal.cancel()
                }
            }
            try {
                withContext(decodeSlots) {
                    try {
                        decode(resolver, file, side, signal)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private fun key(file: FileEntry, side: Int): String = "${file.uri}|${file.modifiedAt}|${file.sizeBytes}|$side"

    private fun decode(resolver: ContentResolver, file: FileEntry, side: Int, signal: CancellationSignal): Bitmap? {
        val uri = file.uri
        val fromProvider = runCatching {
            resolver.loadThumbnail(uri, Size(side, side), signal)
        }.getOrNull()
        if (fromProvider != null) return fromProvider
        return when (file.kind) {
            DocumentKind.Pdf -> firstPage(resolver, uri, side)
            DocumentKind.Video -> videoFrame(resolver, uri, side)
            else -> null
        }
    }

    /** Page 0 of a PDF, rendered at [side] pixels on its longest edge; a locked or damaged file gives null. */
    private fun firstPage(resolver: ContentResolver, uri: Uri, side: Int): Bitmap? =
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return@use null
                renderer.openPage(0).use { page ->
                    val scale = side.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

    /** A frame from near the start of a video, for providers that have no thumbnail of their own. */
    private fun videoFrame(resolver: ContentResolver, uri: Uri, side: Int): Bitmap? =
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(descriptor.fileDescriptor)
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side)
            } finally {
                retriever.release()
            }
        }
}
