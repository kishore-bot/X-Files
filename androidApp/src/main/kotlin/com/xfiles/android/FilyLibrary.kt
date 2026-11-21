package com.xfiles.android

import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.LibrarySearchRepository
import com.xfiles.documents.DocumentKind
import com.xfiles.feature.library.LibraryViewModel
import com.xfiles.feature.reader.DocumentAnalysisViewModel
import com.xfiles.feature.reader.ReaderState
import com.xfiles.fily.FilyBookmark
import com.xfiles.fily.FilyDocument
import com.xfiles.fily.FilyDocuments
import com.xfiles.fily.FilyExcerpt
import com.xfiles.fily.ReaderPosition
import com.xfiles.fily.ReaderTarget
import kotlinx.coroutines.flow.first

/** What Fily may read: the open file, recents and the linked folders, nothing beyond the user's grants. */
internal class FilyLibrary(
    private val library: LibraryViewModel,
    private val reader: DocumentAnalysisViewModel,
    private val documents: DocumentRepository,
    private val search: LibrarySearchRepository,
) : FilyDocuments {
    override suspend fun searchContent(query: String, limit: Int): List<FilyDocument> =
        search.indexedMatches(query, files(), limit).map { it.forFily() }

    fun files(): List<FileEntry> {
        val folder = library.library.value
        val selected = (reader.state.value as? ReaderState.Ready)?.file
        return (listOfNotNull(selected) + library.recents.value + if (folder.scanning) emptyList() else folder.files)
            .distinctBy(FileEntry::uri)
    }

    override fun all(): List<FilyDocument> {
        val recents = library.recents.value
        // Recents are kept newest first, so their position stands in for "last opened".
        val opened = recents.withIndex().associate { (index, file) -> file.uri to (recents.size - index).toLong() }
        return files().map { it.forFily(opened[it.uri]) }
    }

    override fun recent(): List<FilyDocument> = library.recents.value.take(9).map { it.forFily() }

    override fun resolve(id: String): FilyDocument? = file(id)?.forFily()

    override suspend fun text(id: String, limit: Int): FilyExcerpt? {
        val file = file(id) ?: return null
        if (file.kind !in READABLE) return null
        val text = documents.extractText(file, limit + 1)
        return FilyExcerpt(text.take(limit), text.length > limit)
    }

    override fun bookmarks(): List<FilyBookmark> =
        library.bookmarks.value.map { FilyBookmark(it.key, it.file.forFily(), it.page?.plus(1)) }

    /** Opens [id] at [page] (0-based); false while unsaved annotations would be lost. */
    suspend fun open(id: String, page: Int = 0): Boolean {
        val file = file(id) ?: return false
        if (reader.markup.value.marks.isNotEmpty() || reader.markup.value.saving) return false
        reader.open(file.uri, page)
        val opened = reader.state.first { it != ReaderState.Loading } as? ReaderState.Ready ?: return false
        return opened.file.uri == file.uri && opened.preview != FilePreview.Unsupported
    }

    fun file(id: String): FileEntry? = files().firstOrNull { it.uri.toString() == id }

    private companion object {
        val READABLE = setOf(DocumentKind.Text, DocumentKind.Document, DocumentKind.Pdf)
    }
}

private fun FileEntry.forFily(opened: Long? = null) = FilyDocument(uri.toString(), name, sizeBytes, modifiedAt, opened)

internal class FilyPdfTarget(private val reader: DocumentAnalysisViewModel) : ReaderTarget {
    override val operations = setOf("next_page", "previous_page", "go_to_page")
    override val position: ReaderPosition
        get() = current()?.let { ReaderPosition(page = it.page + 1, pageCount = it.pageCount) } ?: ReaderPosition()

    override suspend fun perform(operation: String, value: String?): String? {
        val initial = reader.state.value as? ReaderState.Ready ?: return "no_document"
        val pdf = initial.preview as? FilePreview.Pdf ?: return "unsupported_here"
        if (initial.turningPage) return "not_available"
        val page = when (operation) {
            "next_page" -> pdf.page + 1
            "previous_page" -> pdf.page - 1
            "go_to_page" -> (value?.toIntOrNull() ?: return "missing_arguments") - 1
            else -> return "unsupported_here"
        }
        if (page !in 0 until pdf.pageCount) return "out_of_range"
        if (page == pdf.page) return null
        reader.changePage(page)
        val end = reader.state.first { it !is ReaderState.Ready || !it.turningPage } as? ReaderState.Ready
        val rendered = end?.preview as? FilePreview.Pdf
        return if (end != null && end.file.uri == initial.file.uri && rendered?.page == page) null else "not_available"
    }

    private fun current(): FilePreview.Pdf? = (reader.state.value as? ReaderState.Ready)?.preview as? FilePreview.Pdf
}
