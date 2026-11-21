package com.xfiles.core.documents

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xfiles.documentengine.InvalidPdfSelection
import com.xfiles.documentengine.PdfCompressSummary
import com.xfiles.documentengine.PdfOperation
import com.xfiles.documentengine.PdfPageEdit
import com.xfiles.documentengine.PdfPasswordRequired
import com.xfiles.documentengine.PdfRewriteReport
import com.xfiles.documentengine.compressPdf
import com.xfiles.documentengine.inspectPdfTool
import com.xfiles.documentengine.rewritePdf
import com.xfiles.documentengine.signPdf
import com.xfiles.documentengine.splitPdf
import com.xfiles.documentengine.watermarkPdf
import com.xfiles.documentengine.writePdfMarkup
import com.xfiles.documents.PdfCompressQuality
import com.xfiles.documents.PdfMarkup
import com.xfiles.documents.PdfProtectionChoice
import com.xfiles.documents.statusLine
import com.xfiles.documents.PdfPageSpan
import com.xfiles.documents.PdfSignSpec
import com.xfiles.documents.PdfWatermarkSpec
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
    /** What happened to the protection, in words. Null when the source wasn't protected. */
    val protectionNote: String? = null,
)

/** A PDF copied into the workspace for one of the dedicated tools. Release it when done. */
class OpenedPdf(
    val uri: Uri,
    val file: File,
    val pageCount: Int,
    val password: String?,
    val signed: Boolean,
    val archival: Boolean,
)

class CompressedPdf(val prepared: PreparedPdf, val summary: PdfCompressSummary)

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

    /** Copies [uri] into the workspace and reads what the tools need. Throws [PdfToolPasswordError]. */
    suspend fun open(uri: Uri, password: String?): OpenedPdf = withContext(io) {
        ensureWorkspace()
        val file = File.createTempFile("tool-source-", ".pdf", cache)
        try {
            stage(uri, file, INPUT_BUDGET)
            val info = inspectPdfTool(file, password)
            OpenedPdf(uri, file, info.pages, password, info.signed, info.archival)
        } catch (error: PdfPasswordRequired) {
            file.delete()
            throw PdfToolPasswordError(error.wrong)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun release(opened: OpenedPdf) {
        opened.file.delete()
    }

    /**
     * Pages in the given order and turns. A full permutation uses the strict
     * reorder op, which the engine checks. A subset (after deletions) is a rebuild.
     */
    suspend fun reorder(opened: OpenedPdf, pages: List<PdfPageEdit>, onProgress: (Int, Int) -> Unit): PreparedPdf =
        produce { work ->
            val permutation = pages.size == opened.pageCount && pages.map { it.page }.toSet().size == opened.pageCount
            val operation = if (permutation) PdfOperation.Reorder(pages) else PdfOperation.Rebuild(pages)
            val report: PdfRewriteReport = rewritePdf(listOf(opened.file), work, operation, opened.password, onProgress)
            PreparedPdf(work, report.pages, report.droppedMetadata, opened.signed, opened.archival)
        }

    /** Compacts at [quality]. A protected source is protected again as [protection] says. */
    suspend fun compress(
        opened: OpenedPdf,
        quality: PdfCompressQuality,
        protection: PdfProtectionChoice,
        onProgress: (Int, Int) -> Unit,
    ): CompressedPdf = produce { work ->
        val summary = compressPdf(opened.file, work, opened.password, quality, protection, onProgress)
        CompressedPdf(
            prepared = PreparedPdf(
                work,
                summary.pages,
                droppedMetadata = false,
                signedSource = opened.signed,
                archivalSource = opened.archival,
                protectionNote = summary.protection.statusLine(summary.restrictionsKept),
            ),
            summary = summary,
        )
    }

    suspend fun watermark(
        opened: OpenedPdf,
        spec: PdfWatermarkSpec,
        protection: PdfProtectionChoice,
        onProgress: (Int, Int) -> Unit,
    ): PreparedPdf =
        produce { work ->
            val summary = watermarkPdf(opened.file, work, spec, opened.password, protection, onProgress)
            PreparedPdf(
                work,
                summary.pages,
                droppedMetadata = false,
                signedSource = opened.signed,
                archivalSource = opened.archival,
                protectionNote = summary.protection.statusLine(summary.restrictionsKept),
            )
        }

    suspend fun sign(
        opened: OpenedPdf,
        spec: PdfSignSpec,
        protection: PdfProtectionChoice,
        onProgress: (Int, Int) -> Unit,
    ): PreparedPdf =
        produce { work ->
            val summary = signPdf(opened.file, work, spec, opened.password, protection, onProgress)
            PreparedPdf(
                work,
                summary.pages,
                droppedMetadata = false,
                signedSource = opened.signed,
                archivalSource = opened.archival,
                protectionNote = summary.protection.statusLine(summary.restrictionsKept),
            )
        }

    /** One output per span, in order. The caller saves or discards each. */
    suspend fun split(opened: OpenedPdf, spans: List<PdfPageSpan>, onProgress: (Int, Int) -> Unit): List<PreparedPdf> {
        var outputs: List<File> = emptyList()
        var complete = false
        try {
            val parts = withContext(io) {
                ensureWorkspace()
                val files = spans.map { File.createTempFile("xfiles-part-", ".pdf", cache) }
                outputs = files
                mapToolErrors {
                    val reports = splitPdf(opened.file, spans, files, opened.password, onProgress)
                    reports.zip(files).map { (report, file) ->
                        PreparedPdf(file, report.pages, report.droppedMetadata, opened.signed, opened.archival)
                    }
                }
            }
            complete = true
            return parts
        } finally {
            if (!complete) outputs.forEach { it.delete() }
        }
    }

    /**
     * Creates one document per part in the picked folder, under the given names.
     * If any write fails, the documents already created are removed.
     */
    suspend fun saveAll(parts: List<PreparedPdf>, names: List<String>, folder: Uri): List<Uri> = withContext(io) {
        require(parts.size == names.size) { "Each part needs a name." }
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val created = mutableListOf<Uri>()
        try {
            for ((part, name) in parts.zip(names)) {
                currentCoroutineContext().ensureActive()
                val target = DocumentsContract.createDocument(resolver, parent, "application/pdf", safeName(name))
                    ?: throw IOException("Can't create $name in that folder")
                created += target
                val stream = resolver.openOutputStream(target, "wt") ?: throw IOException("Can't write $name")
                stream.use { out -> part.file.inputStream().use { it.copyTo(out) } }
            }
            created
        } catch (error: Exception) {
            created.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw error
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

    /** Runs one engine call into a fresh workspace file, deleting that file if it fails. */
    private suspend fun <T> produce(block: suspend (File) -> T): T {
        var output: File? = null
        var complete = false
        try {
            val result = withContext(io) {
                ensureWorkspace()
                val work = File.createTempFile("xfiles-pdf-", ".pdf", cache)
                output = work
                mapToolErrors { block(work) }
            }
            complete = true
            return result
        } finally {
            if (!complete) output?.delete()
        }
    }

    private fun ensureWorkspace() {
        if (!cache.isDirectory && !cache.mkdirs()) throw IOException("PDF workspace is unavailable")
    }

    private inline fun <T> mapToolErrors(block: () -> T): T = try {
        block()
    } catch (error: InvalidPdfSelection) {
        throw PdfPageRangeError(error.pageCount)
    } catch (error: PdfPasswordRequired) {
        throw PdfToolPasswordError(error.wrong)
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

        /** Strips characters a document provider rejects, and makes sure the name ends in .pdf. */
        fun safeName(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "Document" }
            return if (cleaned.endsWith(".pdf", ignoreCase = true)) cleaned else "$cleaned.pdf"
        }
    }
}
