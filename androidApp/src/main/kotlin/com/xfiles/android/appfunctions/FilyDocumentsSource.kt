package com.xfiles.android.appfunctions

import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FolderRepository
import com.xfiles.core.documents.LibrarySearchRepository
import com.xfiles.core.documents.MAX_PAGED_CHARS
import com.xfiles.documentengine.DocumentPassages
import com.xfiles.documentengine.PassageSource
import com.xfiles.documents.DocumentKind
import com.xfiles.fily.FilyBookmark
import com.xfiles.fily.FilyDocument
import com.xfiles.fily.FilyDocuments
import com.xfiles.fily.FilyExcerpt
import com.xfiles.fily.FilyPassage
import com.xfiles.fily.FilyRetrieval
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one place that says which files Fily may see: the open document, the recents, and the files in the folders
 * the user linked. The Fily overlay and the Android AppFunctions both read through it, so they can never disagree
 * about what is in reach. Folder scanning is FolderRepository's job; this class only refreshes and lists.
 */
@Singleton
class FilyDocumentsSource @Inject constructor(
    private val documents: DocumentRepository,
    private val folders: FolderRepository,
    private val search: LibrarySearchRepository,
    private val passages: DocumentPassages,
) {
    /** Files in the linked folders as of the last [refresh]; empty until then or when no folder is linked. */
    @Volatile
    private var linked: List<FileEntry> = emptyList()

    /**
     * Loads the stored recents and rescans the linked folders, on IO. Call before a request that needs the current
     * files (the overlay opening, an AppFunction call). A failed folder scan leaves the previous list in place.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        documents.loadRecents()
        linked = if (folders.hasFolders()) {
            try {
                folders.scan().files
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                linked
            }
        } else {
            emptyList()
        }
    }

    /** Fily's view of the library. [selected] is the open document, which Fily can also see. */
    fun view(selected: () -> FileEntry? = { null }): FilyLibraryView = FilyLibraryView(selected)

    /** The library as Fily reads it. Every name lookup and every text read goes through here. */
    inner class FilyLibraryView internal constructor(private val selected: () -> FileEntry?) : FilyDocuments {
        /** The open document, then recents, then linked-folder files, without duplicates. */
        fun files(): List<FileEntry> = (listOfNotNull(selected()) + documents.recents.value + linked).distinctBy(FileEntry::uri)

        fun file(id: String): FileEntry? = files().firstOrNull { it.uri.toString() == id }

        override fun all(): List<FilyDocument> {
            val recents = documents.recents.value
            // Recents are newest first, so their position stands in for "last opened".
            val opened = recents.withIndex().associate { (index, file) -> file.uri to (recents.size - index).toLong() }
            return files().map { it.forFily(opened[it.uri]) }
        }

        override fun recent(): List<FilyDocument> = documents.recents.value.take(RECENT_LIMIT).map { it.forFily() }

        override fun resolve(id: String): FilyDocument? = file(id)?.forFily()

        override suspend fun text(id: String, limit: Int): FilyExcerpt? {
            val file = file(id) ?: return null
            if (file.kind !in READABLE) return null
            val paged = documents.pagedText(file, limit + 1)
            return FilyExcerpt(paged.text.take(limit), paged.text.length > limit, paged.pageStarts)
        }

        /** Searches the file's indexed text; a document that fits [budget] whole is left to [text]. */
        override suspend fun retrieve(id: String, question: String, budget: Int): FilyRetrieval? {
            val file = file(id) ?: return null
            if (file.kind !in READABLE) return null
            val hits = passages.select(file.uri.toString(), file.modifiedAt, file.sizeBytes, question, budget) {
                documents.pagedText(file, MAX_PAGED_CHARS).let { PassageSource(it.text, it.pageStarts) }
            } ?: return null
            if (hits.documentBytes <= budget) return FilyRetrieval.Whole
            return FilyRetrieval.Passages(hits.passages.map { FilyPassage(it.text, it.page) }, hits.omitted)
        }

        override fun bookmarks(): List<FilyBookmark> =
            documents.bookmarks.value.map { FilyBookmark(it.key, it.file.forFily(), it.page?.plus(1)) }

        override suspend fun searchContent(query: String, limit: Int): List<FilyDocument> =
            search.indexedMatches(query, files(), limit).map { it.forFily() }
    }

    private companion object {
        const val RECENT_LIMIT = 9
        val READABLE = setOf(DocumentKind.Text, DocumentKind.Document, DocumentKind.Pdf)
    }
}

internal fun FileEntry.forFily(opened: Long? = null) = FilyDocument(uri.toString(), name, sizeBytes, modifiedAt, opened)
