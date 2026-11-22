package com.xfiles.core.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.xfiles.documentengine.DocumentAnalyzer
import com.xfiles.documentengine.InvalidPdfSelection
import com.xfiles.documentengine.PdfOperation
import com.xfiles.documentengine.PdfPasswordRequired
import com.xfiles.documentengine.PdfTextLine
import com.xfiles.documentengine.rewritePdf
import com.xfiles.documentengine.writePdfTextLayer
import com.xfiles.documents.DocumentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** A long conversion: [Progress] events, then exactly one [Done]. Collect it to run it; cancel to stop. */
sealed interface Conversion<out T> {
    data class Progress(val done: Int, val total: Int?) : Conversion<Nothing>
    data class Done<T>(val result: T) : Conversion<T>
}

/** How many pages of a picture got searchable text: [pagesWithText] of [pages]. */
data class TextLayerOutcome(val pagesWithText: Int, val pages: Int)

/** A picture PDF and, when searchable text was requested, what the text layer achieved (null otherwise). */
data class ComposedPdf(val pdf: PreparedPdf, val textLayer: TextLayerOutcome?)

/** One page of a live text preview: a bitmap of [pageIndex] (clamped to the text's pages) out of [pageCount]. */
class TextPagePreview(val bitmap: Bitmap, val pageIndex: Int, val pageCount: Int)

/** Pictures written from a PDF: [count] of them, and how many were rendered smaller than asked to fit the limits. */
data class PageImagesResult(val count: Int, val reducedPages: Int)

class UnprintableDocument : IllegalArgumentException("Sound and video files can't be printed.")

/**
 * Printing, PDF conversions and the create tools. PdfRenderer and PdfDocument are not thread-safe, so
 * all page work runs on one IO lane; the Rust engine keeps its own worker threads behind that lane.
 */
@Singleton
class ConversionRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val pdfs: AndroidPdfDocument,
    private val recognition: TextRecognitionRepository,
) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val analyzer = DocumentAnalyzer(resolver)
    private val cache = File(app.cacheDir, "conversions")
    private val lane = Dispatchers.IO.limitedParallelism(1)

    /** The last text laid out, kept so paging through a preview and then exporting doesn't redo the layout. Lane-only. */
    private var textLayout: TextPdfLayout? = null

    // --- Create ---------------------------------------------------------------------------------

    /**
     * Pictures (gallery or camera) as one PDF, one picture per page, stored at [quality]. With [searchable]
     * each page is then read on-device and an invisible text layer is added in one Rust update, so the text
     * selects and searches while the pictures stay as they were. Progress: composing, then reading, page by page.
     */
    fun imagesToPdf(
        images: List<Uri>,
        paper: PdfPaper?,
        searchable: Boolean = false,
        quality: PdfImageQuality = PdfImageQuality.Balanced,
    ): Flow<Conversion<ComposedPdf>> = channelFlow {
        require(images.size in 1..MAX_IMAGES) { "Choose 1 to $MAX_IMAGES pictures." }
        val composed = workFile(".pdf")
        var layered: File? = null
        try {
            val total = if (searchable) images.size * 2 else images.size
            val pages = composed.outputStream().buffered().use { out ->
                PdfComposer.writePictures(images.asSequence().map { decodeBounded(it, quality.maxSidePx) }, paper, quality, out) { done ->
                    trySend(Conversion.Progress(done, total))
                }
            }
            val lines = if (searchable) readPictures(images, paper) { done -> send(Conversion.Progress(images.size + done, total)) } else emptyList()
            val result = if (lines.isEmpty()) {
                composed
            } else {
                val withText = workFile(".pdf")
                layered = withText
                writePdfTextLayer(composed, withText, lines)
                composed.delete()
                withText
            }
            val outcome = if (searchable) TextLayerOutcome(pagesWithText = lines.map { it.page }.distinct().size, pages = pages) else null
            send(Conversion.Done(ComposedPdf(PreparedPdf(result, pages, false, false, false), outcome)))
        } catch (error: Throwable) {
            composed.delete()
            layered?.delete()
            throw error
        }
    }.flowOn(lane)

    /**
     * Each picture read on-device, one at a time, into lines boxed in page fractions. A picture that
     * can't be read contributes no text rather than failing the whole PDF; cancellation still stops it.
     */
    private suspend fun readPictures(images: List<Uri>, paper: PdfPaper?, onPicture: suspend (Int) -> Unit): List<PdfTextLine> {
        val lines = ArrayList<PdfTextLine>()
        images.forEachIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            val bitmap = decodeBounded(uri, MAX_PICTURE_PX)
            try {
                val layout = PdfComposer.layout(bitmap.width, bitmap.height, paper)
                val read = try {
                    recognition.lines(bitmap)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    emptyList()
                }
                val xScale = layout.drawnWidth / bitmap.width
                val yScale = layout.drawnHeight / bitmap.height
                for (line in read) {
                    val text = line.text.trim()
                    if (text.isEmpty()) continue
                    val box = line.box
                    lines += PdfTextLine(
                        page = index,
                        left = (layout.left + box.left * xScale) / layout.pageWidth,
                        top = (layout.top + box.top * yScale) / layout.pageHeight,
                        right = (layout.left + box.right * xScale) / layout.pageWidth,
                        bottom = (layout.top + box.bottom * yScale) / layout.pageHeight,
                        text = text,
                    )
                }
            } finally {
                bitmap.recycle()
            }
            onPicture(index + 1)
        }
        return lines
    }

    /**
     * Gallery pictures added to an existing PDF: appended, or inserted before page [beforePage] (1-based).
     * Pictures become pages first, then the Rust engine merges them, keeping the PDF's own pages intact.
     */
    fun addPicturesToPdf(
        pdf: Uri,
        password: String?,
        pictures: List<Uri>,
        paper: PdfPaper?,
        beforePage: Int?,
        quality: PdfImageQuality = PdfImageQuality.Balanced,
    ): Flow<Conversion<PreparedPdf>> = channelFlow {
        require(pictures.size in 1..MAX_IMAGES) { "Choose 1 to $MAX_IMAGES pictures." }
        val base = stagePlainPdf(pdf, password)
        val pages = workFile(".pdf")
        val output = workFile(".pdf")
        try {
            pages.outputStream().buffered().use { out ->
                PdfComposer.writePictures(pictures.asSequence().map { decodeBounded(it, quality.maxSidePx) }, paper, quality, out) { done ->
                    trySend(Conversion.Progress(done, pictures.size * 2))
                }
            }
            val operation = if (beforePage == null) PdfOperation.Merge else PdfOperation.Insert(beforePage)
            val report = try {
                rewritePdf(listOf(base, pages), output, operation, null) { done, total ->
                    trySend(Conversion.Progress(pictures.size + done * pictures.size / total.coerceAtLeast(1), pictures.size * 2))
                }
            } catch (error: InvalidPdfSelection) {
                throw PdfPageRangeError(error.pageCount)
            }
            send(Conversion.Done(PreparedPdf(output, report.pages, report.droppedMetadata, report.signedSource, report.archivalSource)))
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            base.delete()
            pages.delete()
        }
    }.flowOn(lane)

    /** The text as a PDF in [style]: one page per [TextPdfLayout] page, with the template's decoration. */
    suspend fun textToPdf(text: String, style: TextPdfStyle): PreparedPdf = withContext(lane) {
        require(text.isNotBlank()) { "Type some text first." }
        require(text.length <= MAX_TEXT) { "Text to PDF takes up to ${MAX_TEXT / 1000}k characters." }
        val target = workFile(".pdf")
        try {
            val pages = target.outputStream().buffered().use { PdfComposer.writeText(layoutFor(text, style), it) }
            PreparedPdf(target, pages, false, false, false)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** One page of the text as [style] draws it, [widthPx] wide, for the live preview. */
    suspend fun textPreview(text: String, style: TextPdfStyle, pageIndex: Int, widthPx: Int): TextPagePreview = withContext(lane) {
        val layout = layoutFor(text, style)
        val index = pageIndex.coerceIn(0, layout.pages.lastIndex)
        TextPagePreview(layout.render(index, widthPx.coerceIn(1, MAX_PREVIEW_WIDTH_PX)), index, layout.pages.size)
    }

    // --- Convert --------------------------------------------------------------------------------

    /**
     * Each page as a [format] picture at [dpi] in the folder the user granted. Pages are rendered and encoded
     * one at a time, and a page too large for the pixel limits is rendered smaller; the result counts those.
     * A picture that fails to write is removed again rather than left half-written.
     */
    fun pdfToImages(
        pdf: Uri,
        password: String?,
        folder: Uri,
        baseName: String,
        format: PageImageFormat,
        dpi: Int,
    ): Flow<Conversion<PageImagesResult>> = channelFlow {
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        withRenderer(pdf, password) { renderer ->
            val total = renderer.pageCount
            require(total <= MAX_PAGES) { "Convert PDFs up to $MAX_PAGES pages." }
            var reduced = 0
            renderedPages(renderer, dpi).forEachIndexed { index, rendered ->
                val bitmap = rendered.bitmap
                if (rendered.reduced) reduced++
                try {
                    currentCoroutineContext().ensureActive()
                    val name = "$baseName page ${(index + 1).toString().padStart(total.toString().length, '0')}.${format.extension}"
                    val bytes = ByteArrayOutputStream().also {
                        if (!bitmap.compress(format.compressFormat, format.quality, it)) throw IOException("Page ${index + 1} could not be encoded.")
                    }.toByteArray()
                    createPicture(parent, name, format.mimeType, bytes)
                } finally {
                    bitmap.recycle()
                }
                send(Conversion.Progress(index + 1, total))
            }
            send(Conversion.Done(PageImagesResult(total, reduced)))
        }
    }.flowOn(lane)

    /** Writes one picture into [parent]; a failed write removes the new file so no half-written picture is left. */
    private fun createPicture(parent: Uri, name: String, mimeType: String, bytes: ByteArray) {
        val created = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: throw IOException("The folder refused a new picture.")
        try {
            val out = resolver.openOutputStream(created, "w") ?: throw IOException("Can't write to the folder.")
            out.use { it.write(bytes) }
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }
    }

    /** The PDF's text through the Rust engine, which also reads PDFs Android's renderer has no text API for. */
    suspend fun pdfToText(file: FileEntry): String = withContext(lane) {
        pdfs.text(file.uri, null, MAX_TEXT + 1, ocr = true, ocrPages = 200).also {
            require(it.isNotBlank()) { "No text could be read from this PDF." }
        }.take(MAX_TEXT)
    }

    /** The original bytes, deflated into a .zip at [destination]. */
    suspend fun zip(source: FileEntry, destination: Uri) = writeTo(destination) { out ->
        val input = resolver.openInputStream(source.uri) ?: throw IOException("Can't open the file.")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(source.name.replace('/', '_')))
            input.use { copyBounded(it, zip) }
            zip.closeEntry()
        }
    }

    suspend fun saveText(destination: Uri, text: String) = writeTo(destination) { out ->
        out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    suspend fun save(pdf: PreparedPdf, destination: Uri) = writeTo(destination) { out ->
        pdf.file.inputStream().use { it.copyTo(out) }
        pdf.file.delete()
    }

    // --- Print ----------------------------------------------------------------------------------

    /**
     * A plain PDF the print spooler can read: PDFs are copied (and unlocked with [password]), pictures
     * and text are laid out on A4, and other documents print their text.
     */
    suspend fun printable(file: FileEntry, password: String?): PreparedPdf = withContext(lane) {
        when (file.kind) {
            DocumentKind.Pdf -> {
                val target = stagePlainPdf(file.uri, password)
                val pages = try {
                    ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use(PdfRenderer::getPageCount) }
                } catch (error: Throwable) {
                    target.delete()
                    if (error is SecurityException) throw PdfToolPasswordError(wrong = false) else throw error
                }
                PreparedPdf(target, pages, false, false, false)
            }
            DocumentKind.Image -> {
                val target = workFile(".pdf")
                val quality = PdfImageQuality.High
                val pages = target.outputStream().buffered().use {
                    PdfComposer.writePictures(sequenceOf(decodeBounded(file.uri, quality.maxSidePx)), PdfPaper.A4, quality, it)
                }
                PreparedPdf(target, pages, false, false, false)
            }
            DocumentKind.Text, DocumentKind.Document -> {
                val text = analyzer.extractText(file.uri, file.name, MAX_TEXT.toUInt())
                require(text.isNotBlank()) { "This document has no printable text." }
                val target = workFile(".pdf")
                val style = TextPdfStyle.of(TextPdfTemplate.MinimalLetter, PdfPaper.A4)
                val pages = target.outputStream().buffered().use { PdfComposer.writeText(TextPdfLayout(text, style), it) }
                PreparedPdf(target, pages, false, false, false)
            }
            DocumentKind.Audio, DocumentKind.Video, DocumentKind.Unknown -> throw UnprintableDocument()
        }
    }

    // --- Shared ---------------------------------------------------------------------------------

    /** The layout for [text] in [style], reused while both stay the same. Lane-only. */
    private fun layoutFor(text: String, style: TextPdfStyle): TextPdfLayout {
        textLayout?.takeIf { it.style == style && it.text == text }?.let { return it }
        return TextPdfLayout(text, style).also { textLayout = it }
    }

    /** Opens [pdf] for rendering; a password-protected PDF is first unlocked into a private copy. */
    private suspend fun <T> withRenderer(pdf: Uri, password: String?, block: suspend (PdfRenderer) -> T): T {
        val plain = stagePlainPdf(pdf, password)
        try {
            val descriptor = ParcelFileDescriptor.open(plain, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (error: SecurityException) {
                descriptor.close()
                throw PdfToolPasswordError(wrong = false)
            }
            return renderer.use { block(it) }
        } finally {
            plain.delete()
        }
    }

    /** A rendered page and whether it was made smaller than asked for. */
    private class RenderedPage(val bitmap: Bitmap, val reduced: Boolean)

    /** Renders pages lazily at [dpi], each within the pixel limits; the caller recycles each bitmap. */
    private fun renderedPages(renderer: PdfRenderer, dpi: Int): Sequence<RenderedPage> = (0 until renderer.pageCount).asSequence().map { index ->
        renderer.openPage(index).use { page ->
            val size = pageRenderSize(page.width.toFloat(), page.height.toFloat(), dpi)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            RenderedPage(bitmap, size.reduced)
        }
    }

    private suspend fun stagePlainPdf(source: Uri, password: String?): File {
        val copy = workFile(".pdf")
        try {
            (resolver.openInputStream(source) ?: throw IOException("Can't open the PDF.")).use { input ->
                copy.outputStream().buffered().use { copyBounded(input, it) }
            }
            if (password.isNullOrEmpty()) return copy
            val unlocked = workFile(".pdf")
            try {
                rewritePdf(listOf(copy), unlocked, PdfOperation.Unlock, password) { _, _ -> }
            } catch (error: PdfPasswordRequired) {
                unlocked.delete()
                throw PdfToolPasswordError(error.wrong)
            } catch (error: Throwable) {
                unlocked.delete()
                throw error
            }
            return unlocked.also { copy.delete() }
        } catch (error: Throwable) {
            copy.delete()
            throw error
        }
    }

    /**
     * Decodes a picture with its EXIF turn applied, its longest side at most [maxSide]. ImageDecoder
     * subsamples while decoding, so a large photo never exists at full size in memory.
     */
    private fun decodeBounded(uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
            }
            // Wide-gamut photos would otherwise keep their own colours, which a PDF's DeviceRGB misreads.
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // PdfDocument and JPEG encoding need software pixels.
        }

    private suspend fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_INPUT_BYTES) throw IOException("Files over ${MAX_INPUT_BYTES / (1024 * 1024)} MB are not supported here.")
            output.write(buffer, 0, count)
        }
    }

    /** Writes to a document the user created with the system picker, deleting it again on failure. */
    private suspend fun writeTo(destination: Uri, write: suspend (java.io.OutputStream) -> Unit) = withContext(lane) {
        try {
            val out = resolver.openOutputStream(destination, "wt") ?: throw IOException("Can't open the selected destination.")
            out.use { write(it) }
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }

    private fun workFile(suffix: String): File {
        if (!cache.isDirectory && !cache.mkdirs()) throw IOException("The conversion workspace is unavailable.")
        return File.createTempFile("xfiles-", suffix, cache)
    }

    private companion object {
        const val MAX_IMAGES = 100
        const val MAX_PAGES = 500
        const val MAX_TEXT = 1_000_000
        /** The longest side read for text recognition, which needs detail more than a small file does. */
        const val MAX_PICTURE_PX = 2400
        const val MAX_PREVIEW_WIDTH_PX = 1600
        const val MAX_INPUT_BYTES = 128L * 1024 * 1024
    }
}
