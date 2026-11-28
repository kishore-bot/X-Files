package com.xfiles.core.documents

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.xfiles.engine.ffi.ArchiveCreateJob
import com.xfiles.engine.ffi.NativeArchiveFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One step of a zip job: entries finished so far, bytes moved, and the entry being worked on. */
data class ZipJobProgress(
    val entriesDone: Int,
    val entriesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val current: String,
)

/** A zip entry as listed for the extract picker. Directories have [size] 0. */
data class ZipEntryInfo(val name: String, val size: Long, val isDir: Boolean, val encrypted: Boolean)

enum class ArchiveOutputFormat(val extension: String, val label: String, val mime: String, internal val native: NativeArchiveFormat) {
    Zip("zip", "ZIP", "application/zip", NativeArchiveFormat.ZIP),
    Tar("tar", "TAR", "application/x-tar", NativeArchiveFormat.TAR),
    TarGzip("tar.gz", "TAR.GZ", "application/gzip", NativeArchiveFormat.TAR_GZIP),
    SevenZip("7z", "7z", "application/x-7z-compressed", NativeArchiveFormat.SEVEN_ZIP);

    fun fileName(name: String): String {
        val base = name.trim().ifBlank { "Archive" }
        require(base.none { it in "/\\:\u0000" } && base != "." && base != "..") { "Enter a valid archive name" }
        return if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
    }
}

/** Storage Access Framework adapter for the shared archive engine. */
@Singleton
class ZipArchives @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ArchiveRepository,
) {
    private val resolver get() = context.contentResolver
    private val lane = Dispatchers.IO.limitedParallelism(2)

    suspend fun list(archive: Uri): List<ZipEntryInfo> = repository.entries(archive).map {
        ZipEntryInfo(it.path, it.size, it.isDirectory, it.encrypted)
    }

    suspend fun compress(
        sources: List<Uri>,
        destinationTree: Uri,
        outputName: String,
        format: ArchiveOutputFormat,
        password: String?,
        onProgress: (ZipJobProgress) -> Unit,
    ): Uri = withContext(lane) {
        val workspace = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "archive-create-").toFile()
        try {
            val inputs = File(workspace, "inputs").apply { check(mkdir()) }
            var staged = 0
            var stagedBytes = 0L
            suspend fun stage(uri: Uri, name: String, parent: File, tree: Uri?, docId: String?, depth: Int): File {
                currentCoroutineContext().ensureActive()
                check(depth <= 64 && ++staged <= 20_000) { "The source folder exceeds the archive limit" }
                require(name.isNotEmpty() && name != "." && name != ".." && name.none { it in "/\\:\u0000" }) { "Invalid source name" }
                val file = File(parent, name)
                check(!file.exists()) { "Files in the same folder must have unique names" }
                onProgress(ZipJobProgress(0, 0, 0, 0, name))
                if (tree == null || docId == null) {
                    var size = 0L
                    openInput(uri).use { input ->
                        file.outputStream().use { output ->
                            copy(input, output, checkSize = { count ->
                                size += count
                                stagedBytes += count
                                if (size > MAX_FILE_BYTES || stagedBytes > MAX_INPUT_BYTES) {
                                    throw ArchiveIoException("The selection exceeds the archive size limit")
                                }
                            })
                        }
                    }
                    return file
                }
                check(file.mkdir()) { "Could not stage the source folder" }
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                resolver.query(children, columns, null, null, null)?.use { rows ->
                    while (rows.moveToNext()) {
                        val id = rows.getString(0)
                        val child = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        val directory = rows.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        stage(child, rows.getString(1), file, tree.takeIf { directory }, id.takeIf { directory }, depth + 1)
                    }
                } ?: throw ArchiveIoException("Could not read the source folder")
                return file
            }
            val local = sources.mapIndexed { index, uri ->
                val docId = if (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.getTreeDocumentId(uri)
                } else null
                val name = displayName(uri, docId)
                val slot = File(inputs, index.toString()).apply { check(mkdir()) }
                stage(uri, name, slot, uri.takeIf { docId != null }, docId, 0).absolutePath
            }
            val output = File(workspace, "output")
            onProgress(ZipJobProgress(0, 0, 0, 0, "Creating archive"))
            val count = ArchiveCreateJob().use { native ->
                coroutineScope {
                    // The wait must run outside the bounded native lane so cancellation can signal the worker.
                    val writer = async(Dispatchers.IO) { native.write(local, output.absolutePath, format.native, password) }
                    val poller = launch {
                        while (true) {
                            val progress = native.progress()
                            onProgress(ZipJobProgress(
                                progress.entriesDone.toInt(), progress.entriesTotal.toInt(),
                                progress.bytesDone.toLong(), progress.bytesTotal.toLong(), "Creating archive",
                            ))
                            delay(120)
                        }
                    }
                    try {
                        writer.await().toInt()
                    } catch (cancelled: CancellationException) {
                        native.cancel()
                        withContext(NonCancellable) { writer.join() }
                        throw cancelled
                    } finally {
                        poller.cancel()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val parent = DocumentsContract.buildDocumentUriUsingTree(destinationTree, DocumentsContract.getTreeDocumentId(destinationTree))
            val uri = DocumentsContract.createDocument(resolver, parent, format.mime, outputName)
                ?: throw ArchiveIoException("Could not create $outputName")
            try {
                output.inputStream().use { input ->
                    resolver.openOutputStream(uri)?.use { copy(input, it, output.length(), onProgress) }
                        ?: throw ArchiveIoException("Could not save $outputName")
                }
                onProgress(ZipJobProgress(count, count, output.length(), output.length(), outputName))
                uri
            } catch (failure: Exception) {
                withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
                throw failure
            }
        } finally {
            withContext(NonCancellable) { workspace.deleteRecursively() }
        }
    }

    suspend fun extract(
        archive: Uri,
        destinationTree: Uri,
        selected: Set<String>?,
        password: String?,
        onProgress: (ZipJobProgress) -> Unit,
    ): Int {
        val paths = list(archive).filter { selected == null || it.name in selected }
        repository.exportToTree(archive, paths.map { it.name }, destinationTree, password).collect {
            onProgress(ZipJobProgress(it.entriesDone, it.entriesTotal, it.bytesDone, it.bytesTotal, ""))
        }
        return paths.count { !it.isDir }
    }

    private suspend fun copy(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long = 0,
        onProgress: (ZipJobProgress) -> Unit = {},
        checkSize: (Int) -> Unit = {},
    ) {
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        var reported = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            checkSize(count)
            output.write(buffer, 0, count)
            bytes += count
            if (total > 0 && bytes - reported >= 1024 * 1024) {
                onProgress(ZipJobProgress(0, 0, bytes, total, "Saving archive"))
                reported = bytes
            }
        }
    }

    private fun displayName(uri: Uri, docId: String?): String {
        val column = OpenableColumns.DISPLAY_NAME
        if (docId != null) {
            val target = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            resolver.query(target, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
            return docId.substringAfterLast('/')
        }
        resolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private companion object {
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_INPUT_BYTES = 2L * 1024 * 1024 * 1024
    }

    private fun openInput(uri: Uri) = resolver.openInputStream(uri)
        ?: throw ArchiveIoException("Cannot read a selected file")
}

class ArchiveIoException(message: String) : java.io.IOException(message)
