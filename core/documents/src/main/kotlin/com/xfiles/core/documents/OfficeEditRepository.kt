package com.xfiles.core.documents

import android.content.Context
import android.net.Uri
import com.xfiles.documentengine.OfficeEditException
import com.xfiles.documentengine.OfficeEditSession
import com.xfiles.documentengine.OfficeFindQuery
import com.xfiles.documentengine.OfficeReplaceReport
import com.xfiles.documentengine.OfficeSearchState
import com.xfiles.documentengine.StructuredDocumentReader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** The session's working copy after a replacement, and what it replaced. */
data class OfficeEdit(val file: File?, val report: OfficeReplaceReport)

/**
 * Find and replace for DOCX, PPTX, ODT, ODP and ODS. The engine reads files by
 * path, so the document is staged into a private workspace first. Every native
 * call runs on one IO lane, so a slow edit never runs beside another native
 * call, and a cancelled coroutine stops the Rust work at its next checkpoint.
 *
 * A [OfficeEditSession] follows one working copy and keeps its match index in
 * native memory. Repeated Next/Prev never rescan; a single replacement rescans
 * only the edited part.
 */
@Singleton
class OfficeEditRepository @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver
    private val workspace = File(context.cacheDir, WORKSPACE)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val reader = StructuredDocumentReader(resolver)

    /** Copies [uri] into the workspace, refusing anything over [MAX_INPUT_BYTES]. */
    suspend fun stage(uri: Uri, name: String): File = withContext(io) {
        val extension = extensionOf(name)
        val target = File(workspace, "source-${System.nanoTime()}.$extension")
        ensureWorkspace()
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
        target
    }

    /** A session on [source]. Close it when the document goes away. */
    suspend fun open(source: File): OfficeEditSession = withContext(io) { OfficeEditSession(source) }

    /** Releases a session's native handle. */
    suspend fun close(session: OfficeEditSession): Unit = withContext(io) { session.close() }

    /**
     * Builds the match index for [query] in bounded steps. Emits the state after
     * each step, so a caller can show progress; the last emission is `done`.
     * Collecting stops the native work: the flow is cancelled with its collector.
     */
    fun index(session: OfficeEditSession, query: OfficeFindQuery): Flow<OfficeSearchState> = flow {
        var state = cancelWith(session) { session.startSearch(query) }
        emit(state)
        while (!state.done) {
            state = cancelWith(session) { session.advanceSearch(PARTS_PER_STEP) }
            emit(state)
        }
    }.flowOn(io)

    /** Context of match [index] in the session's current index. */
    suspend fun snippet(session: OfficeEditSession, index: Int): String = withContext(io) { session.snippet(index) }

    /**
     * Replaces matches in the session's working copy and writes the result to a
     * new workspace file, which becomes the working copy. [OfficeEdit.file] is
     * null when nothing was replaced. [name] supplies the file extension.
     */
    suspend fun replace(
        session: OfficeEditSession,
        name: String,
        query: OfficeFindQuery,
        replacement: String,
        onlyIndex: Int?,
    ): OfficeEdit = withContext(io) {
        ensureWorkspace()
        val target = File(workspace, "edited-${System.nanoTime()}.${extensionOf(name)}")
        val report = cancelWith(session) { session.replace(target, query, replacement, onlyIndex) }
        OfficeEdit(file = target.takeIf { report.replaced > 0 && it.isFile }, report = report)
    }

    /** The edited copy as a preview. Its text is not the saved original until the user saves it. */
    suspend fun preview(file: File, name: String): FilePreview.Structured = withContext(io) {
        FilePreview.Structured(reader.readFile(file, name))
    }

    /** Writes [file] to a location the user picked (Storage Access Framework). */
    suspend fun saveCopy(file: File, destination: Uri) = withContext(io) {
        val output = resolver.openOutputStream(destination, "wt") ?: throw IOException("The copy could not be written.")
        output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    }

    /** Deletes workspace files. Files outside the workspace, such as the user's original, are never touched. */
    suspend fun discardAll(files: List<File>): Unit = withContext(io) {
        files.forEach { file ->
            if (file.parentFile == workspace) file.delete()
        }
    }

    private suspend fun copyBounded(input: InputStream, target: File) {
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

    /** Cancels the native work when the coroutine is cancelled, and detaches once the work returns. */
    private suspend fun <T> cancelWith(session: OfficeEditSession, block: () -> T): T {
        val handle: DisposableHandle? = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) session.cancel()
        }
        try {
            return block()
        } finally {
            handle?.dispose()
        }
    }

    private fun ensureWorkspace() {
        if (!workspace.isDirectory && !workspace.mkdirs()) throw IOException("The edit workspace is unavailable.")
    }

    private fun extensionOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in EDITABLE_EXTENSIONS) { "Find and replace supports DOCX, PPTX, ODT, ODP and ODS" }
        return extension
    }

    companion object {
        /** Engine input limit for editing; matches the engine's max_input_bytes. */
        const val MAX_INPUT_BYTES = 256L * 1024L * 1024L
        val EDITABLE_EXTENSIONS = setOf("docx", "pptx", "odt", "odp", "ods")
        private const val WORKSPACE = "office-edit"
        private const val COPY_BUFFER = 64 * 1024
        /** Parts indexed per native step. Small, so cancellation and progress stay responsive. */
        private const val PARTS_PER_STEP = 2
        private const val UNREADABLE = "The document could not be opened."
        private const val TOO_LARGE = "This file is too large to edit."
    }
}
