package com.xfiles.core.documents

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.content.PdfPageTextContent
import android.graphics.pdf.models.PageMatchBounds
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val textIo = Dispatchers.IO.limitedParallelism(1)
    private val ocrRender = Mutex()
    private val ocrCache = LruCache<String, List<PdfTextLine>>(64)

    /** Per-page character geometry for selection; bounded by page count. */
    private val textCache = LruCache<String, PdfPageText>(TEXT_CACHE_PAGES)

    /** Small page bitmaps for the page grid and scrubber, by document, page and size. Bounded in bytes. */
    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        MemoryPressure.register { trim() }
    }

    /** Drops the cached OCR text and page bitmaps; they are rebuilt on demand. Safe from any thread (LruCache is synchronized). */
    fun trim() {
        ocrCache.evictAll()
        thumbnails.evictAll()
        textCache.evictAll()
    }

    /** Every PDF can be searched: through the text layer, or through OCR where there is none. */
    val canSearch: Boolean get() = true

    val supportsIndexText: Boolean
        get() = Build.VERSION.SDK_INT >= 35 || supportsPdfExtensions()

    suspend fun indexText(uri: Uri, limit: Int): String = PerformanceTrace.onLane(
        "XfilesPdfIndexText", "XfilesPdfTextQueue", textIo,
    ) {
        check(supportsIndexText) { "PDF text API is unavailable" }
        layerText(uri, null, limit).limited(limit).text
    }

    /**
     * Up to [limit] characters of the PDF's text, pages separated by blank lines. With [ocr], a PDF with no
     * readable text layer (a scan, or an Android without the text API) is read from its rendered pages,
     * at most [ocrPages] of them. Without it (library indexing, Tidy) only the cheap text layer is used.
     */
    suspend fun text(uri: Uri, password: String?, limit: Int, ocr: Boolean, ocrPages: Int = MAX_OCR_PAGES): String =
        pagedText(uri, password, limit, ocr, ocrPages).text

    /**
     * [text] with the offset where each page starts, so passages can cite page numbers. Every page the reader
     * reached has an entry, blank pages included, so the numbers match the PDF.
     */
    suspend fun pagedText(uri: Uri, password: String?, limit: Int, ocr: Boolean, ocrPages: Int = MAX_OCR_PAGES): PagedText =
        withContext(textIo) {
            val layer = if (supportsIndexText) layerText(uri, password, limit) else PagedText("", emptyList())
            if (layer.text.isNotBlank() || !ocr) return@withContext layer.limited(limit)
            val out = StringBuilder()
            val starts = ArrayList<Int>()
            forEachOcrPage(uri, password, ocrPages) { _, lines ->
                if (out.isNotEmpty() && lines.isNotEmpty()) out.append("\n\n")
                starts += out.length
                lines.joinTo(out, "\n") { it.text }
                out.length < limit
            }
            PagedText(out.toString(), starts).limited(limit)
        }

    private suspend fun layerText(uri: Uri, password: String?, limit: Int): PagedText {
        val out = StringBuilder()
        val starts = ArrayList<Int>()
        fun add(page: String) {
            val body = page.trim()
            if (body.isNotEmpty() && out.isNotEmpty()) out.append("\n\n")
            starts += out.length
            out.append(body)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            openRenderer(uri, password).use { pdf ->
                for (index in 0 until pdf.pageCount) {
                    currentCoroutineContext().ensureActive()
                    pdf.openPage(index).use { page -> add(page.textContents.joinToString("\n") { it.text.orEmpty() }) }
                    if (out.length >= limit) break
                    if (index % 8 == 7) yield()
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
                    if (index % 8 == 7) yield()
                }
            }
        }
        return PagedText(out.toString(), starts)
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
    ): Boolean {
        val revision = revisionKey(uri, password)
        return openRenderer(uri, password).use { pdf ->
            val count = minOf(pdf.pageCount, maxPages)
            for (index in 0 until count) {
                currentCoroutineContext().ensureActive()
                if (!page(index, recognizeCached(pdf, index, revision))) return@use true
            }
            count < pdf.pageCount
        }
    }

    private suspend fun recognizeCached(pdf: PdfRenderer, index: Int, revision: String?): List<PdfTextLine> =
        ocrRender.withLock {
            val key = revision?.let { "$it|$index" }
            key?.let(ocrCache::get)?.let { return@withLock it }
            val lines = PerformanceTrace.span("XfilesPdfOcrPage") { recognizePage(pdf, index) }
            if (key != null) ocrCache.put(key, lines)
            lines
        }

    private suspend fun recognizePage(pdf: PdfRenderer, index: Int): List<PdfTextLine> {
        val bitmap = pdf.openPage(index).use { page ->
            val scale = OCR_EDGE.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
            renderBitmap(page, scale)
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
        val incomplete = forEachOcrPage(uri, password, MAX_OCR_SEARCH_PAGES) { index, lines ->
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
        return PdfSearchResult(results, truncated = incomplete)
    }

    suspend fun render(uri: Uri, index: Int, password: String?): FilePreview.Pdf {
        var bitmap: Bitmap? = null
        try {
            return PerformanceTrace.onLane("XfilesPdfRender", "XfilesPdfRenderQueue", io) {
                openRenderer(uri, password).use { pdf ->
                    if (pdf.pageCount <= 0) throw IOException("PDF has no pages")
                    val pageIndex = index.coerceIn(0, pdf.pageCount - 1)
                    pdf.openPage(pageIndex).use { page ->
                        val scale = minOf(2f, MAX_BITMAP_EDGE.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1))
                        val rendered = renderBitmap(page, scale).also { bitmap = it }
                        FilePreview.Pdf(rendered, pageIndex, pdf.pageCount, canSearch)
                    }
                }
            }
        } catch (error: Throwable) {
            // The bitmap has not been returned to a viewer if dispatch or rendering failed.
            bitmap?.recycle()
            throw error
        }
    }

    /** Renders only the visible portion of a zoomed page, using the same renderer and password path. */
    suspend fun renderRegion(uri: Uri, index: Int, password: String?, region: PdfRasterRegion): Bitmap {
        require(region.left in 0 until region.right && region.right <= region.pageWidth &&
            region.top in 0 until region.bottom && region.bottom <= region.pageHeight)
        var owned: Bitmap? = null
        try {
            return PerformanceTrace.onLane("XfilesPdfRegion", "XfilesPdfRenderQueue", io) {
                currentCoroutineContext().ensureActive()
                openRenderer(uri, password).use { pdf ->
                    pdf.openPage(index).use { page ->
                        val pixels = region.width.toLong() * region.height
                        val scale = minOf(1.0, 4096.0 / maxOf(region.width, region.height),
                            kotlin.math.sqrt(4_000_000.0 / pixels))
                        val width = (region.width * scale).toInt().coerceAtLeast(1)
                        val height = (region.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        owned = bitmap
                        val sx = width.toFloat() / region.width
                        val sy = height.toFloat() / region.height
                        val transform = android.graphics.Matrix().apply {
                            setScale(sx * region.pageWidth / page.width, sy * region.pageHeight / page.height)
                            postTranslate(-region.left * sx, -region.top * sy)
                        }
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (error: Throwable) {
            owned?.recycle()
            throw error
        }
    }

    private fun renderBitmap(page: PdfRenderer.Page, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(
            (page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        return try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    /**
     * One page as a small bitmap whose longest edge is [maxEdge] px. Cached per document, page and size, so
     * returning to a thumbnail is instant. A request whose caller has left (a grid cell scrolled away) is
     * dropped before it reaches the renderer.
     */
    suspend fun thumbnail(uri: Uri, index: Int, password: String?, maxEdge: Int): Bitmap {
        val edge = maxEdge.coerceIn(32, MAX_THUMBNAIL_EDGE)
        return withContext(io) {
            currentCoroutineContext().ensureActive()
            val key = revisionKey(uri, password)?.let { "$it|$index|$edge" }
            key?.let(thumbnails::get)?.let { return@withContext it }
            openRenderer(uri, password).use { pdf ->
                if (pdf.pageCount <= 0) throw IOException("PDF has no pages")
                pdf.openPage(index.coerceIn(0, pdf.pageCount - 1)).use { page ->
                    val scale = edge.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
                    renderBitmap(page, scale).also { if (key != null) thumbnails.put(key, it) }
                }
            }
        }
    }

    /**
     * Per-character geometry of one page, for selecting text: the text layer's bounds where the platform gives
     * them (Android 15, or 12-14 with the PDF module), otherwise OCR lines of the rendered page. Cached per page,
     * built on the IO lane, so a second selection on the page is instant. Scanned pages are OCR'd once.
     */
    suspend fun pageText(uri: Uri, password: String?, index: Int): PdfPageText = withContext(io) {
        val key = revisionKey(uri, password)?.let { "$it|$index" }
        key?.let(textCache::get)?.let { return@withContext it }
        currentCoroutineContext().ensureActive()
        val layer = if (supportsIndexText) layerRuns(uri, password, index) else emptyList()
        val built = if (layer.any { it.text.isNotBlank() }) {
            PdfPageText.build(layer)
        } else {
            PdfPageText.build(ocrLines(uri, password, index).map { PdfTextRun(it.text, listOf(it.box)) })
        }
        if (key != null) textCache.put(key, built)
        built
    }

    /** Reuse derived data only when the provider can identify the current readable revision. */
    private fun revisionKey(uri: Uri, password: String?): String? {
        if (password != null) return null
        val columns = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return try {
            resolver.query(uri, columns, null, null, null)?.use { cursor ->
                val sizeColumn = cursor.getColumnIndex(columns[0])
                val modifiedColumn = cursor.getColumnIndex(columns[1])
                if (!cursor.moveToFirst() || sizeColumn < 0 || modifiedColumn < 0 ||
                    cursor.isNull(sizeColumn) || cursor.isNull(modifiedColumn)
                ) return@use null
                val size = cursor.getLong(sizeColumn)
                val modified = cursor.getLong(modifiedColumn)
                if (size <= 0L || modified <= 0L) null else "$uri|$size|$modified"
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** One page's text runs from the platform text layer; empty when the platform has none. */
    private suspend fun layerRuns(uri: Uri, password: String?, index: Int): List<PdfTextRun> {
        if (Build.VERSION.SDK_INT >= 35) {
            return openRenderer(uri, password).use { pdf ->
                if (index !in 0 until pdf.pageCount) return@use emptyList()
                pdf.openPage(index).use { page -> page.textContents.toRuns(page.width, page.height) }
            }
        }
        if (!supportsPdfExtensions()) return emptyList()
        val descriptor = openDescriptor(uri)
        val pdf = try {
            openOlderRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
        return pdf.use { renderer -> if (index in 0 until renderer.pageCount) renderer.runs(index) else emptyList() }
    }

    /** OCR lines of one page, through the same per-document cache as the page-by-page reads. */
    private suspend fun ocrLines(uri: Uri, password: String?, index: Int): List<PdfTextLine> {
        val key = revisionKey(uri, password)
        return openRenderer(uri, password).use { pdf ->
            if (index !in 0 until pdf.pageCount) return@use emptyList()
            recognizeCached(pdf, index, key)
        }
    }

    suspend fun search(uri: Uri, query: String, password: String?): PdfSearchResult = PerformanceTrace.onLane(
        "XfilesPdfSearch", "XfilesPdfTextQueue", textIo,
    ) {
        if (query.isBlank()) return@onLane PdfSearchResult(emptyList(), truncated = false)
        var hasDigitalText = false
        val layer = when {
            Build.VERSION.SDK_INT >= 35 -> openRenderer(uri, password).use { renderer ->
                scan(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        val matches = page.searchText(query).toNormalizedMatches(page.width, page.height)
                        if (!hasDigitalText) {
                            hasDigitalText = matches.isNotEmpty() || page.textContents.any { !it.text.isNullOrBlank() }
                        }
                        matches
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
                renderer.use { pdf ->
                    scan(pdf.pageCount) { index ->
                        val result = pdf.search(index, query, inspectText = !hasDigitalText)
                        hasDigitalText = hasDigitalText || result.second
                        result.first
                    }
                }
            }
            else -> null
        }
        if (layer != null && (layer.pages.isNotEmpty() || hasDigitalText)) layer
        else ocrSearch(uri, query, password)
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
        const val TEXT_CACHE_PAGES = 24
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

    fun search(index: Int, query: String, inspectText: Boolean): Pair<List<List<PdfMatchBox>>, Boolean> =
        renderer.openPage(index).use { page ->
            val matches = page.searchText(query).toNormalizedMatches(page.width, page.height)
            matches to (matches.isNotEmpty() || (inspectText && page.textContents.any { !it.text.isNullOrBlank() }))
        }

    fun text(index: Int): String =
        renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text.orEmpty() } }

    fun runs(index: Int): List<PdfTextRun> =
        renderer.openPage(index).use { page -> page.textContents.toRuns(page.width, page.height) }

    override fun close() = renderer.close()
}

/** Platform text segments as page-fraction runs. Bounds are page points from the top-left corner. */
@SuppressLint("NewApi")
private fun List<PdfPageTextContent>.toRuns(width: Int, height: Int): List<PdfTextRun> {
    if (width <= 0 || height <= 0) return emptyList()
    return map { content ->
        PdfTextRun(
            content.text.orEmpty(),
            content.bounds.orEmpty().map { rect ->
                PdfMatchBox(
                    (rect.left / width).coerceIn(0f, 1f),
                    (rect.top / height).coerceIn(0f, 1f),
                    (rect.right / width).coerceIn(0f, 1f),
                    (rect.bottom / height).coerceIn(0f, 1f),
                )
            },
        )
    }
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
