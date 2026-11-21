package com.xfiles.documentengine

import com.xfiles.documents.parsePdfPageSelection
import com.xfiles.engine.ffi.NativePdfPageEdit
import com.xfiles.engine.ffi.NativePdfSource
import com.xfiles.engine.ffi.NativePdfToolListener
import com.xfiles.engine.ffi.NativePdfToolOp
import com.xfiles.engine.ffi.ReadException
import com.xfiles.engine.ffi.pdfToolInspect
import com.xfiles.engine.ffi.pdfToolRun
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface PdfOperation {
    data object Merge : PdfOperation
    data class Extract(val selection: String) : PdfOperation
    data class Rotate(val selection: String, val degrees: Int) : PdfOperation
    data class Delete(val selection: String) : PdfOperation
    data class Insert(val page: Int) : PdfOperation
    data class Resize(val width: Float, val height: Float) : PdfOperation
    class Protect(val password: String) : PdfOperation
    data object Unlock : PdfOperation
}

data class PdfRewriteReport(val pages: Int, val droppedMetadata: Boolean, val signedSource: Boolean, val archivalSource: Boolean)
class InvalidPdfSelection(val pageCount: Int) : IllegalArgumentException()
class PdfPasswordRequired(val wrong: Boolean) : IllegalArgumentException()

suspend fun rewritePdf(
    files: List<File>,
    destination: File,
    operation: PdfOperation,
    sourcePassword: String?,
    onProgress: (Int, Int) -> Unit,
): PdfRewriteReport {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    val job = coroutine[Job]
    val sources = files.mapIndexed { index, file ->
        NativePdfSource(fd = -1, path = file.absolutePath, password = sourcePassword.takeIf { index == 0 })
    }
    val listener = object : NativePdfToolListener {
        override fun onProgress(done: UInt, total: UInt) = onProgress(done.toInt(), total.toInt())
        override fun isCancelled(): Boolean = job?.isActive == false
    }
    try {
        val information = sources.map { source ->
            coroutine.ensureActive()
            pdfToolInspect(source)
        }
        val native = nativeOperation(operation, information.first().pageCount.toInt())
        coroutine.ensureActive()
        val report = pdfToolRun(sources, native, destination.absolutePath, listener)
        val dropped = report.dropped
        coroutine.ensureActive()
        return PdfRewriteReport(
            pages = report.pageCount.toInt(),
            droppedMetadata = dropped.outlines || dropped.forms || dropped.namedDestinations || dropped.structureTags ||
                dropped.pageLabels || dropped.attachments || dropped.links > 0u,
            signedSource = information.any { it.isSigned },
            archivalSource = information.any { it.archival },
        )
    } catch (_: ReadException.PasswordRequired) {
        throw PdfPasswordRequired(wrong = false)
    } catch (_: ReadException.WrongPassword) {
        throw PdfPasswordRequired(wrong = true)
    }
}

private fun nativeOperation(operation: PdfOperation, pageCount: Int): NativePdfToolOp {
    fun selection(text: String): List<Int> =
        parsePdfPageSelection(text, pageCount) ?: throw InvalidPdfSelection(pageCount)

    return when (operation) {
        PdfOperation.Merge -> NativePdfToolOp.Merge
        is PdfOperation.Extract -> NativePdfToolOp.Rebuild(selection(operation.selection).map { NativePdfPageEdit(it.toUInt(), 0) })
        is PdfOperation.Rotate -> {
            require(operation.degrees in setOf(90, 180, 270))
            val selected = if (operation.selection.isBlank()) null else selection(operation.selection).toHashSet()
            NativePdfToolOp.Rebuild(List(pageCount) { index ->
                NativePdfPageEdit(index.toUInt(), if (selected == null || index in selected) operation.degrees else 0)
            })
        }
        is PdfOperation.Delete -> {
            val selected = selection(operation.selection).toHashSet()
            if (selected.size == pageCount) throw InvalidPdfSelection(pageCount)
            NativePdfToolOp.Rebuild(
                (0 until pageCount).mapNotNull { index ->
                    if (index in selected) null else NativePdfPageEdit(index.toUInt(), 0)
                },
            )
        }
        is PdfOperation.Insert -> {
            if (operation.page !in 1..(pageCount + 1)) throw InvalidPdfSelection(pageCount + 1)
            NativePdfToolOp.InsertPages((operation.page - 1).toUInt())
        }
        is PdfOperation.Resize -> NativePdfToolOp.Resize(operation.width, operation.height)
        is PdfOperation.Protect -> NativePdfToolOp.AddPassword(operation.password)
        PdfOperation.Unlock -> NativePdfToolOp.RemovePassword
    }
}
