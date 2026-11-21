package com.xfiles.core.documents

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.xfiles.documents.documentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class LinkedFolder(val uri: Uri, val name: String)

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
    private val preferences = context.getSharedPreferences("library_folders", Context.MODE_PRIVATE)
    private val io = Dispatchers.IO.limitedParallelism(1)

    suspend fun link(uri: Uri) = withContext(io) {
        require(DocumentsContract.isTreeUri(uri))
        val stored = linkedUris()
        require(uri in stored || stored.size < MAX_FOLDERS) { "Too many folders" }
        // Write lets Tidy up delete a duplicate the user confirmed; keep read-only when the picker gave only that.
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        saveUris((stored + uri).distinct())
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
        var error: String? = null

        for (tree in linkedUris()) {
            currentCoroutineContext().ensureActive()
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val fallback = rootId.substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder" }
            try {
                val name = resolver.query(rootUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                folders.add(LinkedFolder(tree, name ?: fallback))
                if (remaining > 0) remaining = scanFolder(tree, rootId, found, remaining)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (folders.none { it.uri == tree }) folders.add(LinkedFolder(tree, fallback))
                error = "Some folders could not be read. Refresh or add them again."
            }
        }

        FolderLibrary(folders, found.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }), reachedLimit = remaining == 0, error = error)
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
            resolver.query(children, CHILD_COLUMNS, null, null, null)?.use { cursor ->
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
