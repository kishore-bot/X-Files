package com.xfiles.core.documents

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
data class ZipEntryInfo(val name: String, val size: Long, val isDir: Boolean)

/**
 * Zip create and extract for the whole app, on Dispatchers.IO over the Storage Access Framework, so content from
 * any provider (Drive, USB, SD) works. Cancellation is cooperative: cancel the calling coroutine and the next entry
 * or buffer write stops it. A partly written output is left in place, as the system leaves it.
 *
 * Reusable by the reader: [list] reads a zip's entries, [extract] writes selected entries to a folder.
 * Password-protected zips are not supported here (java.util.zip has no AES); those need the engine.
 */
@Singleton
class ZipArchives @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver get() = context.contentResolver

    /** Lists the entries of [archive] for selection. */
    suspend fun list(archive: Uri): List<ZipEntryInfo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ZipEntryInfo>()
        openInput(archive).use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val e = zip.nextEntry ?: break
                    out += ZipEntryInfo(e.name, if (e.isDirectory) 0L else maxOf(e.size, 0L), e.isDirectory)
                    zip.closeEntry()
                }
            }
        }
        out
    }

    /**
     * Writes a zip named [outputName] into the folder [destinationTree] (an OPEN_DOCUMENT_TREE uri). [sources] are
     * file uris (from OPEN_MULTIPLE_DOCUMENTS) and/or folder tree uris, which are added recursively under their name.
     * Returns the new zip's document uri.
     */
    suspend fun compress(
        sources: List<Uri>,
        destinationTree: Uri,
        outputName: String,
        onProgress: (ZipJobProgress) -> Unit = {},
    ): Uri = withContext(Dispatchers.IO) {
        val plan = mutableListOf<Planned>()
        for (src in sources) {
            if (DocumentsContract.isTreeUri(src)) {
                val docId = DocumentsContract.getTreeDocumentId(src)
                val rootName = displayName(src, docId)
                walkFolder(src, docId, rootName, plan)
            } else {
                plan += Planned(src, displayName(src, null), sizeOf(src))
            }
        }
        val bytesTotal = plan.sumOf { it.size }
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            destinationTree,
            DocumentsContract.getTreeDocumentId(destinationTree),
        )
        val zipUri = DocumentsContract.createDocument(resolver, parent, "application/zip", outputName)
            ?: throw ArchiveIoException("Could not create $outputName")
        var bytesDone = 0L
        resolver.openOutputStream(zipUri)?.let { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
                plan.forEachIndexed { index, item ->
                    currentCoroutineContext().ensureActive()
                    onProgress(ZipJobProgress(index, plan.size, bytesDone, bytesTotal, item.name))
                    zip.putNextEntry(ZipEntry(item.name))
                    openInput(item.uri).use { input ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                            bytesDone += n
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw ArchiveIoException("Could not write $outputName")
        onProgress(ZipJobProgress(plan.size, plan.size, bytesDone, bytesTotal, ""))
        zipUri
    }

    /**
     * Extracts [archive] into the folder [destinationTree]. When [only] is non-null, just those entry names are
     * written. Entries with absolute paths or ".." are skipped (zip-slip). Returns the number of files written.
     */
    suspend fun extract(
        archive: Uri,
        destinationTree: Uri,
        only: Set<String>? = null,
        onProgress: (ZipJobProgress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(destinationTree)
        val folders = mutableMapOf("" to rootId)
        val listing = list(archive)
        val wanted = listing.filter { !it.isDir && (only == null || it.name in only) }
        val bytesTotal = wanted.sumOf { it.size }
        var bytesDone = 0L
        var written = 0
        openInput(archive).use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    if (!isSafe(name)) { zip.closeEntry(); continue }
                    if (e.isDirectory) {
                        ensureFolder(destinationTree, folders, name.trimEnd('/'))
                        zip.closeEntry(); continue
                    }
                    if (only != null && name !in only) { zip.closeEntry(); continue }
                    onProgress(ZipJobProgress(written, wanted.size, bytesDone, bytesTotal, name))
                    val dirPart = name.substringBeforeLast('/', "")
                    val fileName = name.substringAfterLast('/')
                    val parentId = ensureFolder(destinationTree, folders, dirPart)
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(destinationTree, parentId)
                    val fileUri = DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", fileName)
                        ?: throw ArchiveIoException("Could not create $fileName")
                    resolver.openOutputStream(fileUri)?.use { out ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = zip.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            bytesDone += n
                        }
                    } ?: throw ArchiveIoException("Could not write $fileName")
                    written++
                    zip.closeEntry()
                }
            }
        }
        onProgress(ZipJobProgress(written, wanted.size, bytesDone, bytesTotal, ""))
        written
    }

    /** Creates (or reuses) the folder chain [path] under the tree root; returns the last folder's document id. */
    private fun ensureFolder(tree: Uri, folders: MutableMap<String, String>, path: String): String {
        folders[path]?.let { return it }
        val parentPath = path.substringBeforeLast('/', "")
        val parentId = ensureFolder(tree, folders, parentPath)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, path.substringAfterLast('/'))
            ?: throw ArchiveIoException("Could not create folder $path")
        val id = DocumentsContract.getDocumentId(created)
        folders[path] = id
        return id
    }

    private data class Planned(val uri: Uri, val name: String, val size: Long)

    private fun walkFolder(tree: Uri, docId: String, prefix: String, plan: MutableList<Planned>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = "$prefix/${c.getString(1)}"
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkFolder(tree, id, name, plan)
                } else {
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    plan += Planned(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size)
                }
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

    private fun sizeOf(uri: Uri): Long =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        } ?: 0L

    private fun openInput(uri: Uri) = resolver.openInputStream(uri) ?: throw ArchiveIoException("Cannot read $uri")

    private fun isSafe(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && name.split('/').none { it == ".." } && !name.contains('\\')

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/** An I/O or provider failure during a zip job. The message is user-facing. */
class ArchiveIoException(message: String) : java.io.IOException(message)
