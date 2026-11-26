package com.xfiles.core.documents

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.documentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray

enum class FolderAccess { Available, Reconnect, Retry }

data class LinkedFolder(val uri: Uri, val name: String, val access: FolderAccess = FolderAccess.Available)

data class FolderLibrary(
    val folders: List<LinkedFolder> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val scanning: Boolean = false,
    val reachedLimit: Boolean = false,
    val error: String? = null,
)

@Singleton
class FolderRepository @Inject constructor(@ApplicationContext context: Context) {
    private val resolver = context.contentResolver
    private val storage = context.getSystemService(StorageManager::class.java)
    private val preferences = context.getSharedPreferences("library_folders", Context.MODE_PRIVATE)
    private val io = Dispatchers.IO.limitedParallelism(1)

    suspend fun link(uri: Uri, replacing: Uri? = null) = withContext(io) {
        require(DocumentsContract.isTreeUri(uri))
        val stored = linkedUris()
        require(uri in stored || (replacing != null && replacing in stored) || stored.size < MAX_FOLDERS) { "Too many folders" }
        // Write lets Tidy up delete a duplicate the user confirmed; keep read-only when the picker gave only that.
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (replacing != null && replacing != uri) unlink(replacing)
        saveUris((linkedUris() + uri).distinct())
    }

    suspend fun unlink(uri: Uri) = withContext(io) {
        saveUris(linkedUris().filterNot { it == uri })
        try {
            val granted = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (granted?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            resolver.releasePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // The provider may already have revoked access.
        }
    }

    suspend fun scan(): FolderLibrary = withContext(io) {
        val folders = mutableListOf<LinkedFolder>()
        val found = LinkedHashMap<String, FileEntry>()
        var remaining = MAX_SCAN_ENTRIES
        val missing = mutableSetOf<Uri>()
        val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        for (tree in linkedUris()) {
            currentCoroutineContext().ensureActive()
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val fallback = rootId.substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder" }
            if (tree !in grants) {
                folders.add(LinkedFolder(tree, fallback, FolderAccess.Reconnect))
                continue
            }
            var name = fallback
            try {
                val root = try {
                    rootName(rootUri, fallback)
                } catch (_: FileNotFoundException) {
                    null
                }
                if (root == null) {
                    if (volumeAvailable(tree, rootId)) missing.add(tree)
                    else folders.add(LinkedFolder(tree, fallback, FolderAccess.Retry))
                    continue
                }
                name = root
                val entries = LinkedHashMap<String, FileEntry>()
                if (remaining > 0) remaining = scanFolder(tree, rootId, entries, remaining)
                entries.forEach { (id, file) -> found.putIfAbsent(id, file) }
                folders.add(LinkedFolder(tree, name))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                folders.add(LinkedFolder(tree, name, FolderAccess.Reconnect))
            } catch (_: Exception) {
                folders.add(LinkedFolder(tree, name, FolderAccess.Retry))
            }
        }

        missing.forEach { unlink(it) }
        val error = "Some folders could not be read. Retry or reconnect the affected folders."
            .takeIf { folders.any { it.access != FolderAccess.Available } }
        FolderLibrary(folders, found.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }), reachedLimit = remaining == 0, error = error)
    }

    private fun volumeAvailable(tree: Uri, rootId: String): Boolean {
        if (tree.authority != "com.android.externalstorage.documents") return true
        val volumeId = rootId.substringBefore(':')
        return storage?.storageVolumes.orEmpty().any { volume ->
            val matches = if (volumeId == "primary" || volumeId == "home") volume.isPrimary
            else volume.uuid.equals(volumeId, ignoreCase = true)
            matches && volume.state in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
        }
    }

    private fun rootName(uri: Uri, fallback: String): String? {
        val columns = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val result = resolver.query(uri, columns, null, null, null)
        if (result != null) {
            return result.use { cursor ->
                if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING) || cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)) {
                    throw IOException("Folder provider unavailable")
                }
                if (!cursor.moveToFirst()) return@use null
                val mimeColumn = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
                val mime = if (mimeColumn >= 0) cursor.getString(mimeColumn) else null
                if (mime == null) throw IOException("Folder provider did not report a document type")
                if (mime != Document.MIME_TYPE_DIR) return@use null
                val nameColumn = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
                if (nameColumn >= 0) cursor.getString(nameColumn).orEmpty().ifBlank { fallback } else fallback
            }
        }
        // DocumentsProvider can swallow FileNotFoundException from query(). A path lookup can report
        // it explicitly. Providers without path support remain in the retry state.
        DocumentsContract.findDocumentPath(resolver, uri)
        throw IOException("Folder provider unavailable")
    }

    private suspend fun scanFolder(
        tree: Uri,
        rootId: String,
        files: MutableMap<String, FileEntry>,
        budget: Int,
    ): Int {
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        visited.add(rootId)
        queue.add(rootId)
        var remaining = budget
        while (queue.isNotEmpty() && remaining > 0) {
            currentCoroutineContext().ensureActive()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, queue.removeFirst())
            val result = resolver.query(children, CHILD_COLUMNS, null, null, null)
                ?: throw IOException("Folder provider unavailable")
            result.use { cursor ->
                if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING) || cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)) {
                    throw IOException("Folder provider unavailable")
                }
                while (remaining > 0 && cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    remaining--
                    val id = cursor.getString(0) ?: continue
                    if (!visited.add(id)) continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == Document.MIME_TYPE_DIR) {
                        queue.add(id)
                    } else if (cursor.getInt(3) and Document.FLAG_VIRTUAL_DOCUMENT == 0) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        val size = if (cursor.isNull(4)) null else cursor.getLong(4)
                        val modified = if (cursor.isNull(5)) null else cursor.getLong(5)
                        files.putIfAbsent("${tree.authority}:$id", FileEntry(uri, name, documentKind(name, mime), size, modified))
                    }
                }
            }
        }
        return remaining
    }

    /**
     * The pictures in the same folder as [file] when that folder lies inside a linked tree; null when it does not
     * (or the provider refuses). Not recursive. Stops at [limit] entries and checks cancellation per child.
     */
    suspend fun imagesBeside(file: Uri, limit: Int): List<FileEntry>? = withContext(io) {
        if (file.scheme != ContentResolver.SCHEME_CONTENT) return@withContext null
        val docId = try {
            DocumentsContract.getDocumentId(file)
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        val tree = linkedUris().firstOrNull { it.authority == file.authority && insideTree(docId, DocumentsContract.getTreeDocumentId(it)) }
            ?: return@withContext null
        val root = DocumentsContract.getTreeDocumentId(tree)
        val slash = docId.lastIndexOf('/')
        val parentId = if (slash >= 0) docId.substring(0, slash) else root
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val images = ArrayList<FileEntry>()
            resolver.query(children, CHILD_COLUMNS, null, null, null)?.use { cursor ->
                while (images.size < limit && cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == Document.MIME_TYPE_DIR || cursor.getInt(3) and Document.FLAG_VIRTUAL_DOCUMENT != 0) continue
                    val kind = documentKind(name, mime)
                    if (kind != DocumentKind.Image) continue
                    val size = if (cursor.isNull(4)) null else cursor.getLong(4)
                    val modified = if (cursor.isNull(5)) null else cursor.getLong(5)
                    images.add(FileEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, kind, size, modified))
                }
            }
            images
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** Whether document [docId] is the tree [root] or lies below it. Tree roots such as "primary:" end in a colon. */
    private fun insideTree(docId: String, root: String): Boolean = when {
        docId == root -> true
        root.endsWith(':') || root.endsWith('/') -> docId.startsWith(root)
        else -> docId.startsWith("$root/")
    }

    /** Whether any folder is linked; cheap, no scan. */
    suspend fun hasFolders(): Boolean = withContext(io) { linkedUris().isNotEmpty() }

    private fun linkedUris(): List<Uri> {
        val values = try {
            JSONArray(preferences.getString("trees", "[]"))
        } catch (_: org.json.JSONException) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until minOf(values.length(), MAX_FOLDERS)) {
                val uri = Uri.parse(values.optString(index))
                if (uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri)) add(uri)
            }
        }
    }

    private fun saveUris(uris: List<Uri>) {
        val values = JSONArray()
        uris.forEach { values.put(it.toString()) }
        preferences.edit().putString("trees", values.toString()).apply()
    }

    private companion object {
        const val MAX_FOLDERS = 20
        const val MAX_SCAN_ENTRIES = 6000
        val CHILD_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
