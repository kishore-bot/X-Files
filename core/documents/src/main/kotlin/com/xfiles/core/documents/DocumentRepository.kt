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
import com.xfiles.documents.isArchiveName
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

/**
 * A saved place in a document. [label] names the place (a PDF page's heading) and [snippet] is a few words of
 * the text there; both are optional, so bookmarks saved before they existed still load.
 */
data class FileBookmark(val file: FileEntry, val page: Int?, val label: String? = null, val snippet: String? = null) {
    val key: String get() = "${file.uri}|${page ?: -1}"
}

/** A recent entry with its open count and the last few open times (epoch milliseconds, oldest first). */
internal data class RecentRecord(val file: FileEntry, val opens: Int, val visits: List<Long>)

/** Enough for frecency: older visits weigh almost nothing after a few weeks. */
private const val MAX_VISITS = 10

private fun JSONObject.visitTimes(): List<Long> {
    val array = optJSONArray("visits") ?: return emptyList()
    return List(array.length()) { array.optLong(it) }.filter { it > 0L }.takeLast(MAX_VISITS)
}

sealed interface FilePreview {
    data object Archive : FilePreview
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
        val previous = readRecords()
        if (!persistReadAccess(file.uri)) return@withContext
        val earlier = previous.firstOrNull { it.file.uri == file.uri }
        val visits = ((earlier?.visits.orEmpty()) + System.currentTimeMillis()).takeLast(MAX_VISITS)
        val entry = RecentRecord(file, opens = (earlier?.opens ?: 0) + 1, visits = visits)
        val updated = (listOf(entry) + previous.filter { it.file.uri != file.uri }).take(MAX_RECENTS)
        saveRecents(updated)
        previous.filter { old -> updated.none { it.file.uri == old.file.uri } }.forEach { releaseIfUnused(it.file.uri) }
        publish(updated)
    }

    /** Forgets one recent entry. The file itself is untouched, so there is nothing to confirm. */
    suspend fun removeRecent(file: FileEntry) = withContext(io) {
        val previous = readRecords()
        val updated = previous.filterNot { it.file.uri == file.uri }
        if (updated.size == previous.size) return@withContext
        saveRecents(updated)
        releaseIfUnused(file.uri)
        publish(updated)
    }

    /** The stored recents, read fresh (for the home-screen widget); leaves the in-memory state alone. */
    suspend fun recentsSnapshot(): List<FileEntry> = withContext(io) { readRecents() }

    /**
     * The stored recents with modified times filled in for pictures (the capped list, one provider query each), so a
     * Newest gallery can order them. Off the main thread; a provider that refuses the query leaves the time null.
     */
    suspend fun recentsWithModifiedTimes(): List<FileEntry> = withContext(io) {
        val files = readRecents()
        val timed = ArrayList<FileEntry>(files.size)
        for (file in files) {
            currentCoroutineContext().ensureActive()
            timed += if (file.kind != DocumentKind.Image) file else file.copy(modifiedAt = modifiedTime(file.uri))
        }
        timed
    }

    private fun modifiedTime(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

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
                    .put("page", it.page ?: -1)
                    .putOpt("label", it.label)
                    .putOpt("snippet", it.snippet),
            )
        }
        preferences.edit().putString("bookmarks", records.toString()).apply()
        mutableBookmarks.value = updated
        previous.map { it.file.uri }.distinct().filter { uri -> updated.none { it.file.uri == uri } }
            .forEach { releaseIfUnused(it) }
        true
    }

    suspend fun preview(file: FileEntry, initialPage: Int = 0, password: String? = null): FilePreview = withContext(io) {
        if (isArchiveName(file.name)) return@withContext FilePreview.Archive
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

    /** Per-character geometry of a PDF page for text selection (cached; see [AndroidPdfDocument.pageText]). */
    suspend fun pageText(file: FileEntry, index: Int, password: String?): PdfPageText =
        pdfs.pageText(file.uri, password, index)

    /** A PDF page as a small bitmap for the page grid (cached; see [AndroidPdfDocument.thumbnail]). */
    suspend fun pageThumbnail(file: FileEntry, index: Int, password: String?, maxEdge: Int): Bitmap =
        pdfs.thumbnail(file.uri, index, password, maxEdge)

    /** Reads at most [limit] bytes of [uri] on the IO dispatcher; the editor passes its cap plus one to detect overflow. */
    suspend fun readBoundedBytes(uri: Uri, limit: Int): ByteArray = withContext(io) {
        val input = resolver.openInputStream(uri) ?: throw IOException("Can't open the file")
        input.use { stream ->
            val buffer = ByteArray(limit)
            var filled = 0
            while (filled < limit) {
                val read = stream.read(buffer, filled, limit - filled)
                if (read < 0) break
                filled += read
            }
            buffer.copyOf(filled)
        }
    }

    /** Writes already-encoded bytes (the text editor's encoding and line endings) to a new copy. */
    suspend fun saveBytesCopy(destination: Uri, bytes: ByteArray) = withContext(io) {
        try {
            val stream = resolver.openOutputStream(destination, "wt") ?: throw IOException("Can't open the selected destination")
            stream.use { it.write(bytes) }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }

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

    /**
     * [extractText] with PDF page offsets, and a higher limit ([MAX_PAGED_CHARS]) for passage retrieval over long
     * documents. Only callers that cache the result should use it: reading a long PDF is slow.
     */
    suspend fun pagedText(file: FileEntry, limit: Int): PagedText {
        val bounded = limit.coerceIn(1, MAX_PAGED_CHARS)
        return if (file.kind == DocumentKind.Pdf) pdfs.pagedText(file.uri, null, bounded, ocr = true)
        else PagedText(analyzer.extractText(file.uri, file.name, bounded.toUInt()), emptyList())
    }

    suspend fun summary(text: String) = analyzer.summarize(text)

    /** Recents ranked by frecency (see [byFrecency]), read fresh from the store. */
    private fun readRecents(): List<FileEntry> =
        readRecords().byFrecency(System.currentTimeMillis(), { it.visits }).map { it.file }

    /** The stored records in last-opened order; [publish] ranks them for display. */
    private fun readRecords(): List<RecentRecord> {
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
                // Records written before counts and visit times existed read as one open with no known times.
                add(RecentRecord(FileEntry(uri, name, kind), opens = record.optInt("opens", 1).coerceAtLeast(1), visits = record.visitTimes()))
            }
        }
    }

    private fun publish(records: List<RecentRecord>) {
        mutableRecents.value = records.byFrecency(System.currentTimeMillis(), { it.visits }).map { it.file }
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
                val label = record.optString("label").takeIf { it.isNotEmpty() }
                val snippet = record.optString("snippet").takeIf { it.isNotEmpty() }
                add(FileBookmark(FileEntry(uri, name, kind), page, label, snippet))
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
        if (readRecords().any { it.file.uri == uri } || readBookmarks().any { it.file.uri == uri }) return
        releaseGrant(uri)
    }

    private fun treeGrant(uri: Uri): Uri? {
        val authority = uri.authority ?: return null
        if (!DocumentsContract.isTreeUri(uri)) return null
        return DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getTreeDocumentId(uri))
    }

    private fun persistedReadGrants(): Set<Uri> =
        resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri }

    private fun saveRecents(records: List<RecentRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            val visits = JSONArray()
            record.visits.forEach(visits::put)
            array.put(
                JSONObject()
                    .put("uri", record.file.uri.toString())
                    .put("name", record.file.name)
                    .put("kind", record.file.kind.name)
                    .put("opens", record.opens)
                    .put("visits", visits),
            )
        }
        preferences.edit().putString("recent_files", array.toString()).apply()
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
