package com.xfiles.core.documents

import android.content.Context
import android.net.Uri
import com.xfiles.documentengine.EditValue
import com.xfiles.documentengine.OfficeEditException
import com.xfiles.documentengine.SheetPane
import com.xfiles.documentengine.SheetSlideEdits
import com.xfiles.documentengine.StoredCell
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Structured edits of spreadsheets (XLSX, ODS cells) and presentations (PPTX slide tools).
 *
 * A document is staged once into a private workspace as a [WorkingCopy]. Each edit writes a new file
 * from the latest working copy and makes it the working copy; the staged original is never written.
 * [saveCopy] writes the working copy to a location the user picked. All native calls share one IO lane,
 * so edits never run beside each other, and a cancelled coroutine stops the Rust work at its checkpoint.
 * Engine failures surface as [OfficeEditException] with the engine's message.
 */
@Singleton
class OfficeDocumentEditor @Inject constructor(
    @ApplicationContext context: Context,
    private val office: OfficeEditRepository,
) {
    private val resolver = context.contentResolver
    private val workspace = File(context.cacheDir, WORKSPACE)
    private val io = Dispatchers.IO.limitedParallelism(1)

    /** A staged document. [source] is the copy made on open; [working] is the latest edit. */
    class WorkingCopy internal constructor(val name: String, val source: File) {
        internal var working: File = source

        val edited: Boolean get() = working != source
    }

    /** Copies [uri] into the workspace (bounded by the engine's input limit). */
    suspend fun stage(uri: Uri, name: String): WorkingCopy = withContext(io) {
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in STRUCTURED_EXTENSIONS) { "This file has no structured editor." }
        ensureWorkspace()
        val target = File(workspace, "source-${System.nanoTime()}.$extension")
        try {
            val input = resolver.openInputStream(uri) ?: throw OfficeEditException(UNREADABLE)
            input.use { copyBounded(it, target) }
        } catch (error: IOException) {
            target.delete()
            throw OfficeEditException(UNREADABLE)
        } catch (error: OfficeEditException) {
            target.delete()
            throw error
        }
        WorkingCopy(name, target)
    }

    /** Frozen panes of [sheet] in the working copy (zero when not frozen). */
    suspend fun readPane(copy: WorkingCopy, sheet: String): SheetPane = withContext(io) {
        SheetSlideEdits.readPane(copy.working, sheet)
    }

    suspend fun readCell(copy: WorkingCopy, sheet: String, row: Int, column: Int): StoredCell = withContext(io) {
        SheetSlideEdits.readCell(copy.working, sheet, row, column)
    }

    /** Writes one cell and returns the edited copy's preview. */
    suspend fun editCell(copy: WorkingCopy, sheet: String, row: Int, column: Int, value: EditValue): FilePreview.Structured {
        withContext(io) {
            val target = newTarget(copy)
            SheetSlideEdits.saveCell(copy.working, target, sheet, row, column, value)
            advance(copy, target)
        }
        return office.preview(copy.working, copy.name)
    }

    suspend fun reorderSlides(copy: WorkingCopy, keep: List<Int>): FilePreview.Structured {
        withContext(io) {
            val target = newTarget(copy)
            SheetSlideEdits.reorderSlides(copy.working, target, keep)
            advance(copy, target)
        }
        return office.preview(copy.working, copy.name)
    }

    suspend fun deleteSlide(copy: WorkingCopy, index: Int): FilePreview.Structured {
        withContext(io) {
            val target = newTarget(copy)
            SheetSlideEdits.deleteSlide(copy.working, target, index)
            advance(copy, target)
        }
        return office.preview(copy.working, copy.name)
    }

    suspend fun duplicateSlide(copy: WorkingCopy, index: Int): FilePreview.Structured {
        withContext(io) {
            val target = newTarget(copy)
            SheetSlideEdits.duplicateSlide(copy.working, target, index)
            advance(copy, target)
        }
        return office.preview(copy.working, copy.name)
    }

    /** The working copy as a preview; it is not the saved original until the user saves it. */
    suspend fun preview(copy: WorkingCopy): FilePreview.Structured = office.preview(copy.working, copy.name)

    /** Writes the working copy to [destination], the location the user picked. */
    suspend fun saveCopy(copy: WorkingCopy, destination: Uri) = withContext(io) {
        val output = resolver.openOutputStream(destination, "wt") ?: throw IOException("The copy could not be written.")
        output.use { stream -> copy.working.inputStream().use { it.copyTo(stream) } }
    }

    /** Deletes this copy's workspace files. The user's original is never touched. */
    suspend fun discard(copy: WorkingCopy): Unit = withContext(io) {
        if (copy.working != copy.source) copy.working.delete()
        copy.source.delete()
    }

    private fun newTarget(copy: WorkingCopy): File {
        ensureWorkspace()
        return File(workspace, "edit-${System.nanoTime()}.${copy.name.substringAfterLast('.', "").lowercase()}")
    }

    /** Makes [target] the working copy and deletes the previous edit (never the source). */
    private fun advance(copy: WorkingCopy, target: File) {
        val previous = copy.working
        copy.working = target
        if (previous != copy.source) previous.delete()
    }

    private suspend fun copyBounded(input: java.io.InputStream, target: File) {
        target.outputStream().use { output ->
            val buffer = ByteArray(COPY_BUFFER)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_INPUT_BYTES) throw OfficeEditException(TOO_LARGE, limit = true)
                output.write(buffer, 0, read)
            }
        }
    }

    private fun ensureWorkspace() {
        if (!workspace.isDirectory && !workspace.mkdirs()) throw IOException("The edit workspace is unavailable.")
    }

    companion object {
        /** Formats this editor can change: spreadsheets (XLSX, ODS) and presentations (PPTX). */
        val STRUCTURED_EXTENSIONS = setOf("xlsx", "xlsm", "ods", "pptx", "pptm")
        private const val WORKSPACE = "office-structured"
        private const val COPY_BUFFER = 64 * 1024
        private const val MAX_INPUT_BYTES = 256L * 1024L * 1024L
        private const val UNREADABLE = "The document could not be opened."
        private const val TOO_LARGE = "This file is too large to edit."
    }
}
