package com.xfiles.core.documents

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xfiles.documentengine.InvalidPdfSelection
import com.xfiles.documentengine.PdfOperation
import com.xfiles.documentengine.PdfPasswordRequired
import com.xfiles.documentengine.rewritePdf
import com.xfiles.documentengine.writePdfMarkup
import com.xfiles.documents.PdfMarkup
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class PdfAction(val label: String, val outputName: String, val inputCount: IntRange) {
    Merge("Merge PDFs", "Merged.pdf", 2..8),
    Extract("Extract pages", "Extracted.pdf", 1..1),
    Rotate("Rotate pages", "Rotated.pdf", 1..1),
    Delete("Delete pages", "Selected pages.pdf", 1..1),
    Insert("Insert PDF pages", "With inserted pages.pdf", 2..2),
    Resize("Resize pages", "Resized.pdf", 1..1),
    Protect("Add password", "Protected.pdf", 1..1),
    Unlock("Remove password", "Unlocked.pdf", 1..1),
}

enum class PdfPaper(val label: String, val width: Float, val height: Float) {
    A4("A4", 595.28f, 841.89f),
    A5("A5", 419.53f, 595.28f),
    Letter("Letter", 612f, 792f),
    Legal("Legal", 612f, 1008f),
}

class PdfToolOptions(
    val pages: String,
    val rotation: Int,
    val paper: PdfPaper,
    val sourcePassword: String?,
    val newPassword: String,
)

data class PreparedPdf(
    val file: File,
    val pages: Int,
    val droppedMetadata: Boolean,
    val signedSource: Boolean,
    val archivalSource: Boolean,
)

class PdfPageRangeError(val pageCount: Int) : IllegalArgumentException()
class PdfToolPasswordError(val wrong: Boolean) : IllegalArgumentException()

@Singleton
class PdfToolsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver
    private val cache = File(context.cacheDir, "pdf-tools")
    private val io = Dispatchers.IO.limitedParallelism(1)

    suspend fun run(
        action: PdfAction,
        sources: List<Uri>,
        options: PdfToolOptions,
        onProgress: (Int, Int) -> Unit,
    ): PreparedPdf {
        require(sources.size in action.inputCount)
        var output: File? = null
        var completed = false
        try {
            val result = withContext(io) {
                if (!cache.isDirectory && !cache.mkdirs()) throw IOException("PDF workspace is unavailable")
                val work = File.createTempFile("xfiles-pdf-", ".pdf", cache)
                output = work
                val staged = mutableListOf<File>()
                try {
                    var remaining = INPUT_BUDGET
                    for (uri in sources) {
                        val copy = File.createTempFile("input-", ".pdf", cache)
                        staged.add(copy)
                        remaining -= stage(uri, copy, remaining)
                    }
                    val operation = when (action) {
                        PdfAction.Merge -> PdfOperation.Merge
                        PdfAction.Extract -> PdfOperation.Extract(options.pages)
                        PdfAction.Rotate -> PdfOperation.Rotate(options.pages, options.rotation)
                        PdfAction.Delete -> PdfOperation.Delete(options.pages)
                        PdfAction.Insert -> PdfOperation.Insert(options.pages.trim().toIntOrNull() ?: 0)
                        PdfAction.Resize -> PdfOperation.Resize(options.paper.width, options.paper.height)
                        PdfAction.Protect -> {
                            require(options.newPassword.encodeToByteArray().size in 1..127) { "Enter a new password up to 127 UTF-8 bytes." }
                            PdfOperation.Protect(options.newPassword)
                        }
                        PdfAction.Unlock -> PdfOperation.Unlock
                    }
                    val report = rewritePdf(staged, work, operation, options.sourcePassword, onProgress)
                    PreparedPdf(work, report.pages, report.droppedMetadata, report.signedSource, report.archivalSource)
                } catch (error: InvalidPdfSelection) {
                    throw PdfPageRangeError(error.pageCount)
                } catch (error: PdfPasswordRequired) {
                    throw PdfToolPasswordError(error.wrong)
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw error
                } finally {
                    staged.forEach { it.delete() }
                }
            }
            completed = true
            return result
        } finally {
            if (!completed) output?.delete()
        }
    }

    suspend fun annotate(source: Uri, marks: List<PdfMarkup>): PreparedPdf {
        require(marks.isNotEmpty())
        var output: File? = null
        var complete = false
        try {
            val prepared = withContext(io) {
                if (!cache.isDirectory && !cache.mkdirs()) throw IOException("PDF workspace is unavailable")
                val original = File.createTempFile("markup-source-", ".pdf", cache)
                try {
                    stage(source, original, INPUT_BUDGET)
                    val target = File.createTempFile("markup-copy-", ".pdf", cache)
                    output = target
                    val pages = writePdfMarkup(original, target, marks)
                    PreparedPdf(target, pages, droppedMetadata = false, signedSource = false, archivalSource = false)
                } finally {
                    original.delete()
                }
            }
            complete = true
            return prepared
        } finally {
            if (!complete) output?.delete()
        }
    }

    suspend fun save(pdf: PreparedPdf, destination: Uri) = withContext(io) {
        try {
            val stream = resolver.openOutputStream(destination, "wt") ?: throw IOException("Can't open the selected destination")
            stream.use { target -> pdf.file.inputStream().use { it.copyTo(target) } }
        } catch (error: Exception) {
            // ACTION_CREATE_DOCUMENT supplies a new file for this export.
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }

    private suspend fun stage(uri: Uri, target: File, budget: Long): Long {
        val input = resolver.openInputStream(uri) ?: throw IOException("Can't open a selected PDF")
        return input.use { source ->
            target.outputStream().buffered().use { sink ->
                val buffer = ByteArray(32_768)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > budget) throw IOException("PDFs exceed the combined size limit")
                    sink.write(buffer, 0, count)
                }
                total
            }
        }
    }

    private companion object {
        const val INPUT_BUDGET = 64L * 1024L * 1024L
    }
}
