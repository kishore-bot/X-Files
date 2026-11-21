package com.xfiles.core.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.xfiles.documentengine.DocumentAnalyzer
import com.xfiles.documentengine.InvalidPdfSelection
import com.xfiles.documentengine.PdfOperation
import com.xfiles.documentengine.PdfPasswordRequired
import com.xfiles.documentengine.rewritePdf
import com.xfiles.documents.DocumentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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

class UnprintableDocument : IllegalArgumentException("Sound and video files can't be printed.")

/**
 * Printing, PDF conversions and the create tools. PdfRenderer and PdfDocument are not thread-safe, so
 * all page work runs on one IO lane; the Rust engine keeps its own worker threads behind that lane.
 */
@Singleton
class ConversionRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val pdfs: AndroidPdfDocument,
) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val analyzer = DocumentAnalyzer(resolver)
    private val cache = File(app.cacheDir, "conversions")
    private val lane = Dispatchers.IO.limitedParallelism(1)

    // --- Create ---------------------------------------------------------------------------------

    /** Pictures (gallery or camera) as one PDF, one picture per page. */
    fun imagesToPdf(images: List<Uri>, paper: PdfPaper?): Flow<Conversion<PreparedPdf>> = channelFlow {
        require(images.size in 1..MAX_IMAGES) { "Choose 1 to $MAX_IMAGES pictures." }
        val target = workFile(".pdf")
        try {
            val pictures = images.asSequence().map { uri -> decodeBounded(uri) }
            val pages = target.outputStream().buffered().use { out ->
                PdfComposer.writePictures(pictures, paper, out) { done -> trySend(Conversion.Progress(done, images.size)) }
            }
            send(Conversion.Done(PreparedPdf(target, pages, false, false, false)))
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }.flowOn(lane)

    /**
     * Gallery pictures added to an existing PDF: appended, or inserted before page [beforePage] (1-based).
     * Pictures become pages first, then the Rust engine merges them, keeping the PDF's own pages intact.
     */
    fun addPicturesToPdf(pdf: Uri, password: String?, pictures: List<Uri>, paper: PdfPaper?, beforePage: Int?): Flow<Conversion<PreparedPdf>> = channelFlow {
        require(pictures.size in 1..MAX_IMAGES) { "Choose 1 to $MAX_IMAGES pictures." }
        val base = stagePlainPdf(pdf, password)
        val pages = workFile(".pdf")
        val output = workFile(".pdf")
        try {
            pages.outputStream().buffered().use { out ->
                PdfComposer.writePictures(pictures.asSequence().map(::decodeBounded), paper, out) { done ->
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

    suspend fun textToPdf(text: String, paper: PdfPaper): PreparedPdf = withContext(lane) {
        require(text.isNotBlank()) { "Type some text first." }
        require(text.length <= MAX_TEXT) { "Text to PDF takes up to ${MAX_TEXT / 1000}k characters." }
        val target = workFile(".pdf")
        try {
            val pages = target.outputStream().buffered().use { PdfComposer.writeText(text, paper, it) }
            PreparedPdf(target, pages, false, false, false)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    // --- Convert --------------------------------------------------------------------------------

    /** Each page as a JPEG in the folder the user granted; emits the number of pictures written. */
    fun pdfToImages(pdf: Uri, password: String?, folder: Uri, baseName: String): Flow<Conversion<Int>> = channelFlow {
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        withRenderer(pdf, password) { renderer ->
            val total = renderer.pageCount
            require(total <= MAX_PAGES) { "Convert PDFs up to $MAX_PAGES pages." }
            renderedPages(renderer).forEachIndexed { index, bitmap ->
                try {
                    currentCoroutineContext().ensureActive()
                    val name = "$baseName page ${(index + 1).toString().padStart(total.toString().length, '0')}.jpg"
                    val created = DocumentsContract.createDocument(resolver, parent, "image/jpeg", name)
                        ?: throw IOException("The folder refused a new picture.")
                    val out = resolver.openOutputStream(created, "w") ?: throw IOException("Can't write to the folder.")
                    out.use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) throw IOException("Page ${index + 1} could not be encoded.") }
                } finally {
                    bitmap.recycle()
                }
                send(Conversion.Progress(index + 1, total))
            }
            send(Conversion.Done(total))
        }
    }.flowOn(lane)

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
                val pages = target.outputStream().buffered().use { PdfComposer.writePictures(sequenceOf(decodeBounded(file.uri)), PdfPaper.A4, it) }
                PreparedPdf(target, pages, false, false, false)
            }
            DocumentKind.Text, DocumentKind.Document -> {
                val text = analyzer.extractText(file.uri, file.name, MAX_TEXT.toUInt())
                require(text.isNotBlank()) { "This document has no printable text." }
                val target = workFile(".pdf")
                val pages = target.outputStream().buffered().use { PdfComposer.writeText(text, PdfPaper.A4, it) }
                PreparedPdf(target, pages, false, false, false)
            }
            DocumentKind.Audio, DocumentKind.Video, DocumentKind.Unknown -> throw UnprintableDocument()
        }
    }

    // --- Shared ---------------------------------------------------------------------------------

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

    /** Renders pages lazily, about 150 dpi and at most 2,400 px on the long side. */
    private fun renderedPages(renderer: PdfRenderer): Sequence<Bitmap> = (0 until renderer.pageCount).asSequence().map { index ->
        renderer.openPage(index).use { page ->
            val scale = min(150f / 72f, 2400f / max(page.width, page.height))
            Bitmap.createBitmap(max(1, (page.width * scale).roundToInt()), max(1, (page.height * scale).roundToInt()), Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
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

    private fun decodeBounded(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_PICTURE_PX) {
                val scale = MAX_PICTURE_PX.toFloat() / longest
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // PdfDocument draws on a software canvas.
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
        const val MAX_PICTURE_PX = 2400
        const val MAX_INPUT_BYTES = 128L * 1024 * 1024
    }
}
