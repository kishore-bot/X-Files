package com.xfiles.android

import com.xfiles.android.appfunctions.FilyDocumentsSource
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.feature.reader.DocumentAnalysisViewModel
import com.xfiles.feature.reader.ReaderState
import com.xfiles.fily.FilyDocuments
import com.xfiles.fily.FilyHandoff
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

    /** Opens [id] at an explicit zero-based [page], or at its saved position. */
    suspend fun open(id: String, page: Int? = null): FilyHandoff {
        val file = file(id) ?: return FilyHandoff.Unavailable
        if (reader.markup.value.saving) return FilyHandoff.Busy
        if (reader.markup.value.marks.isNotEmpty()) return FilyHandoff.UnsavedAnnotations
        reader.open(file.uri, page)
        val opened = reader.state.first { it != ReaderState.Loading } as? ReaderState.Ready
        return if (opened != null && opened.file.uri == file.uri && opened.preview != FilePreview.Unsupported) FilyHandoff.Accepted
        else FilyHandoff.Unavailable
    }

    companion object {
        /** The overlay's view: the document open in the reader counts as Fily's current file too. */
        fun create(source: FilyDocumentsSource, reader: DocumentAnalysisViewModel): FilyLibrary =
            FilyLibrary(source.view { (reader.state.value as? ReaderState.Ready)?.file }, reader)
    }
}

/**
 * The open viewer as Fily drives it: PDF pages when a PDF is open, otherwise the scroll viewer on screen
 * (HTML or spreadsheet) when one is registered. The operations follow what is on screen right now.
 */
internal class FilyViewerTarget(
    private val reader: DocumentAnalysisViewModel,
    private val readerVisible: () -> Boolean,
) : ReaderTarget {
    private val viewer get() = reader.currentScrollViewer.takeIf { readerVisible() }
    // A PDF takes page commands; every viewer that registered a scroller (PDF pages included) takes scrolling.
    override val operations: Set<String>
        get() = (if (current() != null) PAGE_OPERATIONS else emptySet()) + viewer?.operations.orEmpty()
    override val position: ReaderPosition
        get() {
            val ready = (reader.state.value as? ReaderState.Ready).takeIf { readerVisible() }
            val pdf = ready?.preview as? FilePreview.Pdf
            if (ready != null && pdf != null) {
                val visible = reader.visiblePdfPage(ready.file)?.takeIf { it in 0 until pdf.pageCount }
                return ReaderPosition(page = (visible ?: pdf.page) + 1, pageCount = pdf.pageCount)
            }
            return viewer?.position ?: ReaderPosition()
        }

    override suspend fun perform(operation: String, value: String?): String? {
        if (current() == null || operation !in PAGE_OPERATIONS) {
            val scroll = viewer ?: return "unsupported_here"
            return scroll.perform(operation)
        }
        val initial = reader.state.value as? ReaderState.Ready ?: return "no_document"
        val pdf = initial.preview as? FilePreview.Pdf ?: return "unsupported_here"
        if (initial.turningPage) return "not_available"
        val visible = reader.visiblePdfPage(initial.file)?.takeIf { it in 0 until pdf.pageCount } ?: pdf.page
        val page = when (operation) {
            "next_page" -> visible + 1
            "previous_page" -> visible - 1
            "go_to_page" -> (value?.toIntOrNull() ?: return "missing_arguments") - 1
            else -> return "unsupported_here"
        }
        if (page !in 0 until pdf.pageCount) return "out_of_range"
        if (page == visible) return null
        reader.changePage(page)
        val end = reader.state.first { it !is ReaderState.Ready || !it.turningPage } as? ReaderState.Ready
        val rendered = end?.preview as? FilePreview.Pdf
        return if (end != null && end.file.uri == initial.file.uri && rendered?.page == page) null else "not_available"
    }

    private fun current(): FilePreview.Pdf? =
        ((reader.state.value as? ReaderState.Ready)?.preview as? FilePreview.Pdf).takeIf { readerVisible() }
}

private val PAGE_OPERATIONS = setOf("next_page", "previous_page", "go_to_page")
