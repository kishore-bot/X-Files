package com.xfiles.core.documents

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.models.PageMatchBounds
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import androidx.annotation.RequiresExtension
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.xfiles.documents.structure.DocumentError
import com.xfiles.documents.structure.DocumentException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class PdfMatchBox(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class PdfPageMatches(val page: Int, val matches: List<List<PdfMatchBox>>)
data class PdfSearchResult(val pages: List<PdfPageMatches>, val truncated: Boolean)

/**
 * Android's PDF renderer, plus PDF text on every supported version: the text layer where Android exposes
 * it (Android 15, or 12–14 with the PDF module update), otherwise on-device OCR of the rendered pages.
 * OCR results are cached per file, so a second search or summary is instant.
 */
@Singleton
class AndroidPdfDocument @Inject constructor(
    @ApplicationContext context: Context,
    private val recognition: TextRecognitionRepository,
) {
    private val resolver = context.contentResolver
    private val cache = context.cacheDir
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val ocrCache = LruCache<String, Array<List<PdfTextLine>?>>(4)

    /** Small page bitmaps for the page grid and scrubber, by document, page and size. Bounded in bytes. */
    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Every PDF can be searched: through the text layer, or through OCR where there is none. */
    val canSearch: Boolean get() = true

    private val hasTextLayer: Boolean
        get() = Build.VERSION.SDK_INT >= 35 || supportsPdfExtensions()

    /**
     * Up to [limit] characters of the PDF's text, pages separated by blank lines. With [ocr], a PDF with no
     * readable text layer (a scan, or an Android without the text API) is read from its rendered pages,
     * at most [ocrPages] of them. Without it (library indexing, Tidy) only the cheap text layer is used.
     */
    suspend fun text(uri: Uri, password: String?, limit: Int, ocr: Boolean, ocrPages: Int = MAX_OCR_PAGES): String =
        withContext(io) {
            val layer = if (hasTextLayer) layerText(uri, password, limit) else ""
            if (layer.isNotBlank() || !ocr) return@withContext layer.take(limit)
            val out = StringBuilder()
            forEachOcrPage(uri, password, ocrPages) { _, lines ->
                if (lines.isNotEmpty()) {
                    if (out.isNotEmpty()) out.append("\n\n")
                    lines.joinTo(out, "\n") { it.text }
                }
                out.length < limit
            }
            out.take(limit).toString()
        }

    private suspend fun layerText(uri: Uri, password: String?, limit: Int): String {
        val out = StringBuilder()
        fun add(page: String) {
            if (page.isBlank()) return
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(page.trim())
        }
        if (Build.VERSION.SDK_INT >= 35) {
            openRenderer(uri, password).use { pdf ->
                for (index in 0 until pdf.pageCount) {
                    currentCoroutineContext().ensureActive()
                    pdf.openPage(index).use { page -> add(page.textContents.joinToString("\n") { it.text.orEmpty() }) }
                    if (out.length >= limit) break
                }
            }
        } else if (supportsPdfExtensions()) {
            val descriptor = openDescriptor(uri)
            val pdf = try {
                openOlderRenderer(descriptor)
            } catch (error: Exception) {
                descriptor.close()
                throw error
            }
            pdf.use {
                for (index in 0 until it.pageCount) {
                    currentCoroutineContext().ensureActive()
                    add(it.text(index))
                    if (out.length >= limit) break
                }
            }
        }
        return out.toString()
    }

    /**
     * Calls [page] with each page's OCR lines (from the cache when read before) until it returns false.
     * Pages are rendered at a size ML Kit reads reliably, one at a time, and recycled straight away.
     */
    private suspend fun forEachOcrPage(
        uri: Uri,
        password: String?,
        maxPages: Int,
        page: suspend (Int, List<PdfTextLine>) -> Boolean,
    ) {
        val key = uri.toString()
        openRenderer(uri, password).use { pdf ->
            val count = minOf(pdf.pageCount, maxPages)
            val known = ocrCache[key]?.takeIf { it.size == pdf.pageCount } ?: arrayOfNulls<List<PdfTextLine>>(pdf.pageCount)
            ocrCache.put(key, known)
            for (index in 0 until count) {
                currentCoroutineContext().ensureActive()
                val lines = known[index] ?: recognizePage(pdf, index).also { known[index] = it }
                if (!page(index, lines)) break
            }
        }
    }

    private suspend fun recognizePage(pdf: PdfRenderer, index: Int): List<PdfTextLine> {
        val bitmap = pdf.openPage(index).use { page ->
            val scale = OCR_EDGE.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
            Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            ).also {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
        return try {
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            recognition.lines(bitmap).map { line ->
                PdfTextLine(
                    line.text,
                    PdfMatchBox(
                        (line.box.left / width).coerceIn(0f, 1f),
                        (line.box.top / height).coerceIn(0f, 1f),
                        (line.box.right / width).coerceIn(0f, 1f),
                        (line.box.bottom / height).coerceIn(0f, 1f),
                    ),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Search over OCR lines; a match's box is the matched share of its line. */
    private suspend fun ocrSearch(uri: Uri, query: String, password: String?): PdfSearchResult {
        val needle = query.trim()
        val results = ArrayList<PdfPageMatches>()
        var remaining = MAX_MATCHES
        forEachOcrPage(uri, password, MAX_OCR_SEARCH_PAGES) { index, lines ->
            val hits = lines.flatMap { line ->
                val length = line.text.length.coerceAtLeast(1)
                val width = line.box.right - line.box.left
                generateSequence(line.text.indexOf(needle, ignoreCase = true).takeIf { it >= 0 }) { from ->
                    line.text.indexOf(needle, from + needle.length, ignoreCase = true).takeIf { it >= 0 }
                }.map { start ->
                    val left = line.box.left + width * start / length
                    val right = line.box.left + width * (start + needle.length).coerceAtMost(length) / length
                    listOf(PdfMatchBox(left, line.box.top, right, line.box.bottom))
                }.toList()
            }
            if (hits.isNotEmpty()) {
                results.add(PdfPageMatches(index, hits.take(remaining)))
                remaining -= hits.size
            }
            remaining > 0
        }
        return PdfSearchResult(results, truncated = remaining <= 0)
    }

    suspend fun render(uri: Uri, index: Int, password: String?): FilePreview.Pdf = withContext(io) {
        openRenderer(uri, password).use { pdf ->
            if (pdf.pageCount <= 0) throw IOException("PDF has no pages")
            val pageIndex = index.coerceIn(0, pdf.pageCount - 1)
            pdf.openPage(pageIndex).use { page ->
                val scale = minOf(2f, MAX_BITMAP_EDGE.toFloat() / maxOf(page.width, page.height))
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                FilePreview.Pdf(bitmap, pageIndex, pdf.pageCount, canSearch)
            }
        }
    }

    /**
     * One page as a small bitmap whose longest edge is [maxEdge] px. Cached per document, page and size, so
     * returning to a thumbnail is instant. A request whose caller has left (a grid cell scrolled away) is
     * dropped before it reaches the renderer.
     */
    suspend fun thumbnail(uri: Uri, index: Int, password: String?, maxEdge: Int): Bitmap {
        val edge = maxEdge.coerceIn(32, MAX_THUMBNAIL_EDGE)
        val key = "$uri|$index|$edge"
        thumbnails.get(key)?.let { return it }
        return withContext(io) {
            currentCoroutineContext().ensureActive()
            thumbnails.get(key)?.let { return@withContext it }
            openRenderer(uri, password).use { pdf ->
                if (pdf.pageCount <= 0) throw IOException("PDF has no pages")
                pdf.openPage(index.coerceIn(0, pdf.pageCount - 1)).use { page ->
                    val scale = edge.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.also { thumbnails.put(key, it) }
                }
            }
        }
    }

    suspend fun search(uri: Uri, query: String, password: String?): PdfSearchResult = withContext(io) {
        if (query.isBlank()) return@withContext PdfSearchResult(emptyList(), truncated = false)
        when {
            Build.VERSION.SDK_INT >= 35 -> openRenderer(uri, password).use { renderer ->
                scan(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        page.searchText(query).toNormalizedMatches(page.width, page.height)
                    }
                }
            }
            supportsPdfExtensions() -> {
                val descriptor = openDescriptor(uri)
                val renderer = try {
                    openOlderRenderer(descriptor)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
                renderer.use { pdf -> scan(pdf.pageCount) { pdf.search(it, query) } }
            }
            else -> null
        }?.takeIf { it.pages.isNotEmpty() }
            // No text API, or a scanned PDF with no text layer: read the pages instead.
            ?: ocrSearch(uri, query, password)
    }

    private suspend fun scan(count: Int, page: (Int) -> List<List<PdfMatchBox>>): PdfSearchResult {
        val results = ArrayList<PdfPageMatches>()
        var remaining = MAX_MATCHES
        val limit = minOf(count, MAX_SEARCH_PAGES)
        for (index in 0 until limit) {
            currentCoroutineContext().ensureActive()
            if (index % 8 == 0) yield()
            val hits = page(index)
            if (hits.isEmpty()) continue
            results.add(PdfPageMatches(index, hits.take(remaining)))
            val omitted = hits.size > remaining
            remaining -= hits.size
            if (remaining <= 0) return PdfSearchResult(results, truncated = omitted || index + 1 < count)
        }
        return PdfSearchResult(results, truncated = limit < count)
    }

    private suspend fun openRenderer(uri: Uri, password: String?): PdfRenderer {
        val descriptor = openDescriptor(uri)
        return try {
            if (Build.VERSION.SDK_INT >= 35 && password != null) {
                PdfRenderer(descriptor, LoadParams.Builder().setPassword(password).build())
            } else {
                PdfRenderer(descriptor)
            }
        } catch (error: SecurityException) {
            descriptor.close()
            val reason = when {
                Build.VERSION.SDK_INT < 35 -> DocumentError.UnsupportedEncryption("Opening this encrypted PDF requires Android 15.")
                password == null -> DocumentError.PasswordRequired("Enter the PDF password.")
                else -> DocumentError.WrongPassword("That password did not unlock this PDF.")
            }
            throw DocumentException(reason)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
    }

    private suspend fun openDescriptor(uri: Uri): ParcelFileDescriptor {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("PDF could not be opened")
        try {
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            return descriptor
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESPIPE) {
                descriptor.close()
                throw error
            }
        }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val temporary = File.createTempFile("pdf-preview-", ".pdf", cache)
            try {
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        downloaded += bytes
                        if (downloaded > MAX_PROVIDER_BYTES) throw DocumentException(
                            DocumentError.LimitExceeded("The PDF exceeds the reader's size limit."),
                        )
                        output.write(buffer, 0, bytes)
                    }
                }
                ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                temporary.delete()
            }
        }
    }

    @SuppressLint("NewApi")
    private fun openOlderRenderer(descriptor: ParcelFileDescriptor): ExtensionPdfText =
        ExtensionPdfText(descriptor)

    private companion object {
        const val MAX_BITMAP_EDGE = 2048
        const val MAX_SEARCH_PAGES = 1_500
        const val MAX_MATCHES = 1_000
        const val MAX_PROVIDER_BYTES = 128L * 1024 * 1024
        const val OCR_EDGE = 1800
        const val MAX_OCR_PAGES = 40
        const val MAX_OCR_SEARCH_PAGES = 80
        const val MAX_THUMBNAIL_EDGE = 512
        const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024
    }
}

data class PdfTextLine(val text: String, val box: PdfMatchBox)

private const val PDF_EXTENSION_VERSION = 13

private fun supportsPdfExtensions(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PDF_EXTENSION_VERSION

@RequiresExtension(extension = Build.VERSION_CODES.S, version = PDF_EXTENSION_VERSION)
private class ExtensionPdfText(descriptor: ParcelFileDescriptor) : AutoCloseable {
    private val renderer = PdfRendererPreV(descriptor)
    val pageCount: Int get() = renderer.pageCount

    fun search(index: Int, query: String): List<List<PdfMatchBox>> =
        renderer.openPage(index).use { page -> page.searchText(query).toNormalizedMatches(page.width, page.height) }

    fun text(index: Int): String =
        renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text.orEmpty() } }

    override fun close() = renderer.close()
}

@SuppressLint("NewApi")
private fun List<PageMatchBounds>.toNormalizedMatches(width: Int, height: Int): List<List<PdfMatchBox>> {
    if (width <= 0 || height <= 0) return emptyList()
    fun normalize(rect: RectF) = PdfMatchBox(
        (rect.left / width).coerceIn(0f, 1f),
        (rect.top / height).coerceIn(0f, 1f),
        (rect.right / width).coerceIn(0f, 1f),
        (rect.bottom / height).coerceIn(0f, 1f),
    )
    return map { match -> match.bounds.map(::normalize) }.filter { it.isNotEmpty() }
}
