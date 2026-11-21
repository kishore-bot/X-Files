package com.xfiles.android

import com.xfiles.android.appfunctions.FilyDocumentsSource
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.feature.reader.DocumentAnalysisViewModel
import com.xfiles.feature.reader.ReaderState
import com.xfiles.fily.FilyDocuments
import com.xfiles.fily.ReaderPosition
import com.xfiles.fily.ReaderTarget
import kotlinx.coroutines.flow.first

/**
 * The overlay's side of Fily's documents: the shared [FilyDocumentsSource] view (open file, recents, linked
 * folders), plus the one hand-off that needs the open reader. The listing itself lives in the source, so the
 * overlay and the AppFunctions cannot drift apart.
 */
internal class FilyLibrary private constructor(
    private val view: FilyDocumentsSource.FilyLibraryView,
    private val reader: DocumentAnalysisViewModel,
) : FilyDocuments by view {
    fun files(): List<FileEntry> = view.files()

    fun file(id: String): FileEntry? = view.file(id)

    /** Opens [id] at [page] (0-based); false while unsaved annotations would be lost. */
    suspend fun open(id: String, page: Int = 0): Boolean {
        val file = file(id) ?: return false
        if (reader.markup.value.marks.isNotEmpty() || reader.markup.value.saving) return false
        reader.open(file.uri, page)
        val opened = reader.state.first { it != ReaderState.Loading } as? ReaderState.Ready ?: return false
        return opened.file.uri == file.uri && opened.preview != FilePreview.Unsupported
    }

    companion object {
        /** The overlay's view: the document open in the reader counts as Fily's current file too. */
        fun create(source: FilyDocumentsSource, reader: DocumentAnalysisViewModel): FilyLibrary =
            FilyLibrary(source.view { (reader.state.value as? ReaderState.Ready)?.file }, reader)
    }
}

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
