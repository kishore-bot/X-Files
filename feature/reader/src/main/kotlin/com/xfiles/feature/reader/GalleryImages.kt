package com.xfiles.feature.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.util.LruCache
import com.xfiles.core.documents.FileEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Screen-sized decodes of gallery pictures.
 *
 * - Decoding runs on [lane], a two-wide slice of the IO pool, so a swipe never waits behind a large batch.
 * - Each picture is decoded once: concurrent requests (a visible page and a prefetch) share one in-flight decode.
 * - The cache is an LRU bounded by bytes ([LruCache.sizeOf] is the allocation size), so memory stays flat however
 *   long the user pages through a folder. Bitmaps are never recycled: eviction only drops the reference, and Compose
 *   keeps any bitmap it is still drawing alive until it stops.
 * - [retain] cancels decodes for pictures the user has left behind; a cancelled decode that has not started never runs.
 * - Animated pictures ([animation]) are not cached: only the settled page holds one, and it is dropped when the page
 *   stops being current.
 */
@Singleton
class GalleryImages @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver
    private val lane = Dispatchers.IO.limitedParallelism(DECODE_LANES)
    private val scope = CoroutineScope(SupervisorJob() + lane)
    private val cache = object : LruCache<DecodeKey, Bitmap>(budgetBytes()) {
        override fun sizeOf(key: DecodeKey, value: Bitmap): Int = value.allocationByteCount
    }
    /** Guarded by itself: decodes started and not yet finished, keyed by picture and target size. */
    private val inflight = HashMap<DecodeKey, Deferred<Bitmap>>()

    /** The picture decoded to fit within [maxEdge] pixels on its longest side. Throws when the file cannot be decoded. */
    suspend fun load(file: FileEntry, maxEdge: Int): Bitmap {
        val key = DecodeKey(file.uri.toString(), maxEdge)
        cache.get(key)?.let { return it }
        val work = synchronized(inflight) {
            inflight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                currentCoroutineContext().ensureActive()
                decode(file.uri, maxEdge).also { cache.put(key, it) }
            }.also { created ->
                inflight[key] = created
                created.invokeOnCompletion {
                    synchronized(inflight) { if (inflight[key] === created) inflight.remove(key) }
                }
            }
        }
        work.start()
        return work.await()
    }

    /**
     * The animation of a GIF, WebP or AVIF picture, fitted like [load]; null for anything still or any other format.
     * The caller starts and stops it; the drawable is not cached.
     */
    suspend fun animation(file: FileEntry, maxEdge: Int): AnimatedImageDrawable? {
        if (!mayAnimate(file.name)) return null
        return withContext(lane) {
            currentCoroutineContext().ensureActive()
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, file.uri)) { decoder, info, _ ->
                fitTo(decoder, info, maxEdge)
            } as? AnimatedImageDrawable
        }
    }

    /** Cancels decodes for pictures whose URI is not in [keep]. Cached bitmaps stay until the LRU evicts them. */
    fun retain(keep: Set<String>) {
        synchronized(inflight) {
            val iterator = inflight.entries.iterator()
            while (iterator.hasNext()) {
                val (key, work) = iterator.next()
                if (key.uri !in keep) {
                    work.cancel()
                    iterator.remove()
                }
            }
        }
    }

    /** Drops every decode and cached bitmap; used when the reader closes. */
    fun clear() {
        retain(emptySet())
        cache.evictAll()
    }

    private fun decode(uri: Uri, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ -> fitTo(decoder, info, maxEdge) }

    private data class DecodeKey(val uri: String, val maxEdge: Int)

    private companion object {
        const val DECODE_LANES = 2
        const val MIN_BUDGET = 16 * 1024 * 1024
        const val MAX_BUDGET = 64 * 1024 * 1024
        val ANIMATED_EXTENSIONS = setOf("gif", "webp", "avif")

        /** An eighth of the heap, kept between 16 and 64 MiB: enough for the current picture and its neighbours. */
        fun budgetBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceIn(MIN_BUDGET.toLong(), MAX_BUDGET.toLong()).toInt()

        /** Whether a picture of this name can hold an animation worth decoding as one. */
        fun mayAnimate(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in ANIMATED_EXTENSIONS

        /** Asks the decoder for a size whose longest edge is at most [maxEdge]; smaller pictures keep their size. */
        fun fitTo(decoder: ImageDecoder, info: ImageDecoder.ImageInfo, maxEdge: Int) {
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }
}
