package com.xfiles.core.documents

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.xfiles.documentengine.DocumentAnalyzer
import com.xfiles.documentengine.StructuredDocumentReader
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.documentKind
import com.xfiles.documents.structure.StructuredDocument
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FileEntry(
    val uri: Uri,
    val name: String,
    val kind: DocumentKind,
    val sizeBytes: Long? = null,
    val modifiedAt: Long? = null,
)

data class FileBookmark(val file: FileEntry, val page: Int?) {
    val key: String get() = "${file.uri}|${page ?: -1}"
}

sealed interface FilePreview {
    data class Text(val content: String, val truncated: Boolean) : FilePreview
    data class Structured(val document: StructuredDocument, val source: Text? = null) : FilePreview
    /** A local web page: [html] for the sandboxed viewer, [text] for search, glance and Fily. */
    data class Html(val html: String, val text: Text) : FilePreview
    data class Picture(val bitmap: Bitmap) : FilePreview
    data class Pdf(val bitmap: Bitmap, val page: Int, val pageCount: Int, val searchable: Boolean) : FilePreview
    data class Media(val video: Boolean) : FilePreview
    data object Unsupported : FilePreview
}

@Singleton
class DocumentRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val pdfs: AndroidPdfDocument,
) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val analyzer = DocumentAnalyzer(resolver)
    private val structured = StructuredDocumentReader(resolver)
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val mutableRecents = MutableStateFlow<List<FileEntry>>(emptyList())
    val recents = mutableRecents.asStateFlow()
    private val mutableBookmarks = MutableStateFlow<List<FileBookmark>>(emptyList())
    val bookmarks = mutableBookmarks.asStateFlow()

    suspend fun loadRecents() = withContext(io) {
        mutableRecents.value = readRecents()
        mutableBookmarks.value = readBookmarks()
    }

    suspend fun describe(uri: Uri): FileEntry = withContext(io) {
        require(uri.scheme == "content")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        FileEntry(uri, name, documentKind(name, resolver.getType(uri)))
    }

    suspend fun remember(file: FileEntry) = withContext(io) {
        currentCoroutineContext().ensureActive()
        val previous = readRecents()
        if (!persistReadAccess(file.uri)) return@withContext
        val updated = (listOf(file) + previous.filter { it.uri != file.uri }).take(MAX_RECENTS)
        saveRecents(updated)
        previous.filter { old -> updated.none { it.uri == old.uri } }.forEach { releaseIfUnused(it.uri) }
        mutableRecents.value = updated
    }

    /** The stored recents, read fresh (for the home-screen widget); leaves the in-memory state alone. */
    suspend fun recentsSnapshot(): List<FileEntry> = withContext(io) { readRecents() }

    suspend fun clearRecents() = withContext(io) {
        val previous = readRecents()
        saveRecents(emptyList())
        previous.forEach { releaseIfUnused(it.uri) }
        mutableRecents.value = emptyList()
    }

    suspend fun toggleBookmark(bookmark: FileBookmark): Boolean = withContext(io) {
        val previous = readBookmarks()
        val existing = previous.any { it.key == bookmark.key }
        if (!existing && !persistReadAccess(bookmark.file.uri)) return@withContext false
        val updated = if (existing) {
            previous.filterNot { it.key == bookmark.key }
        } else {
            (listOf(bookmark) + previous).take(MAX_BOOKMARKS)
        }
        val records = JSONArray()
        updated.forEach {
            records.put(
                JSONObject()
                    .put("uri", it.file.uri.toString())
                    .put("name", it.file.name)
                    .put("kind", it.file.kind.name)
                    .put("page", it.page ?: -1),
            )
        }
        preferences.edit().putString("bookmarks", records.toString()).apply()
        mutableBookmarks.value = updated
        previous.map { it.file.uri }.distinct().filter { uri -> updated.none { it.file.uri == uri } }
            .forEach { releaseIfUnused(it) }
        true
    }

    suspend fun preview(file: FileEntry, initialPage: Int = 0, password: String? = null): FilePreview = withContext(io) {
        when (file.kind) {
            DocumentKind.Image -> FilePreview.Picture(loadImage(file.uri))
            DocumentKind.Pdf -> pdfs.render(file.uri, initialPage, password)
            DocumentKind.Text -> {
                val source = readText(file.uri)
                if (file.name.substringAfterLast('.', "").lowercase() in FORMATTED_TEXT_EXTENSIONS) {
                    FilePreview.Structured(structured.read(file.uri, file.name, password), source)
                } else {
                    source
                }
            }
            DocumentKind.Document -> {
                if (file.name.substringAfterLast('.', "").lowercase() in HTML_EXTENSIONS) {
                    val text = analyzer.extractText(file.uri, file.name, (MAX_TEXT_CHARACTERS + 1).toUInt())
                    val plain = FilePreview.Text(text.take(MAX_TEXT_CHARACTERS), text.length > MAX_TEXT_CHARACTERS)
                    val source = readText(file.uri)
                    // A truncated page could render broken markup; show its text instead.
                    if (source.truncated) plain else FilePreview.Html(source.content, plain)
                } else {
                    FilePreview.Structured(structured.read(file.uri, file.name, password))
                }
            }
            DocumentKind.Audio -> FilePreview.Media(video = false)
            DocumentKind.Video -> FilePreview.Media(video = true)
            DocumentKind.Unknown -> FilePreview.Unsupported
        }
    }

    suspend fun page(file: FileEntry, index: Int, password: String?): FilePreview.Pdf =
        pdfs.render(file.uri, index, password)

    suspend fun searchPdf(file: FileEntry, query: String, password: String?): PdfSearchResult =
        pdfs.search(file.uri, query, password)

    suspend fun saveTextCopy(destination: Uri, text: String) = withContext(io) {
        require(text.length <= MAX_TEXT_CHARACTERS)
        try {
            val stream = resolver.openOutputStream(destination, "wt") ?: throw IOException("Can't open the selected destination")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        } catch (error: Exception) {
            // ACTION_CREATE_DOCUMENT supplies a new file for this copy.
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }

    /** Plain text for Fily, summaries and checks. PDFs read their text layer, or their pages by OCR. */
    suspend fun extractText(file: FileEntry, limit: Int, password: String? = null): String {
        val bounded = limit.coerceIn(1, 32_001)
        return if (file.kind == DocumentKind.Pdf) pdfs.text(file.uri, password, bounded, ocr = true)
        else analyzer.extractText(file.uri, file.name, bounded.toUInt())
    }

    suspend fun summary(text: String) = analyzer.summarize(text)

    private fun readRecents(): List<FileEntry> {
        val records = try {
            JSONArray(preferences.getString("recent_files", "[]"))
        } catch (_: org.json.JSONException) {
            return emptyList()
        }
        val grants = persistedReadGrants()
        return buildList {
            for (index in 0 until minOf(records.length(), MAX_RECENTS)) {
                val record = records.optJSONObject(index) ?: continue
                val uri = Uri.parse(record.optString("uri"))
                if (uri !in grants && treeGrant(uri) !in grants) continue
                val name = record.optString("name")
                val kind = DocumentKind.entries.find { it.name == record.optString("kind") } ?: documentKind(name, null)
                add(FileEntry(uri, name, kind))
            }
        }
    }

    private fun readBookmarks(): List<FileBookmark> {
        val records = try {
            JSONArray(preferences.getString("bookmarks", "[]"))
        } catch (_: org.json.JSONException) {
            return emptyList()
        }
        val grants = persistedReadGrants()
        return buildList {
            for (index in 0 until minOf(records.length(), MAX_BOOKMARKS)) {
                val record = records.optJSONObject(index) ?: continue
                val uri = Uri.parse(record.optString("uri"))
                if (uri !in grants && treeGrant(uri) !in grants) continue
                val name = record.optString("name")
                val kind = DocumentKind.entries.find { it.name == record.optString("kind") } ?: documentKind(name, null)
                val page = record.optInt("page", -1).takeIf { it >= 0 }
                add(FileBookmark(FileEntry(uri, name, kind), page))
            }
        }
    }

    private fun persistReadAccess(uri: Uri): Boolean {
        val grants = persistedReadGrants()
        if (uri in grants || treeGrant(uri) in grants) return true
        return try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun releaseIfUnused(uri: Uri) {
        if (readRecents().any { it.uri == uri } || readBookmarks().any { it.file.uri == uri }) return
        releaseGrant(uri)
    }

    private fun treeGrant(uri: Uri): Uri? {
        val authority = uri.authority ?: return null
        if (!DocumentsContract.isTreeUri(uri)) return null
        return DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getTreeDocumentId(uri))
    }

    private fun persistedReadGrants(): Set<Uri> =
        resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri }

    private fun saveRecents(files: List<FileEntry>) {
        val records = JSONArray()
        files.forEach {
            records.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("kind", it.kind.name))
        }
        preferences.edit().putString("recent_files", records.toString()).apply()
    }

    private fun releaseGrant(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The provider or system may already have revoked this grant.
        }
    }

    private suspend fun readText(uri: Uri): FilePreview.Text {
        val stream = resolver.openInputStream(uri) ?: throw IOException("Document could not be opened")
        val text = stream.bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            val result = StringBuilder()
            while (result.length <= MAX_TEXT_CHARACTERS) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_TEXT_CHARACTERS + 1 - result.length))
                if (count < 0) break
                result.append(buffer, 0, count)
            }
            result.toString().removePrefix("\uFEFF")
        }
        return FilePreview.Text(text.take(MAX_TEXT_CHARACTERS), text.length > MAX_TEXT_CHARACTERS)
    }

    private fun loadImage(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val longestSide = maxOf(info.size.width, info.size.height)
        if (longestSide > MAX_BITMAP_EDGE) {
            val scale = MAX_BITMAP_EDGE.toFloat() / longestSide
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }

    private companion object {
        val FORMATTED_TEXT_EXTENSIONS = setOf("md", "markdown", "csv", "tsv")
        val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
        const val MAX_RECENTS = 40
        const val MAX_BOOKMARKS = 100
        const val MAX_TEXT_CHARACTERS = 256_000
        const val MAX_BITMAP_EDGE = 2048
    }
}
