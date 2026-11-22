package com.xfiles.feature.reader

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.DocumentSharing
import com.xfiles.core.documents.FileBookmark
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfPageText
import com.xfiles.documents.PdfMarkup
import com.xfiles.feature.reader.domain.BookmarkPlaceUseCase
import com.xfiles.feature.reader.domain.DocumentSummaryUseCase
import com.xfiles.feature.reader.domain.OpenDocumentUseCase
import com.xfiles.feature.reader.domain.PdfPagesUseCase
import com.xfiles.feature.reader.domain.SensitiveScanUseCase
import com.xfiles.feature.reader.domain.pdfPartner
import com.xfiles.tidy.SensitiveFindings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The document reader's state holder. It opens and pages files and publishes immutable state; the work sits in
 * `domain` use cases and in the PDF controllers (markup, find, print), which this class sequences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentAnalysisViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val sharing: DocumentSharing,
    private val saved: SavedStateHandle,
    private val gallery: GalleryController,
    private val structured: com.xfiles.core.documents.OfficeDocumentEditor,
    private val openDocument: OpenDocumentUseCase,
    private val pdfPages: PdfPagesUseCase,
    private val sensitiveScan: SensitiveScanUseCase,
    private val summarize: DocumentSummaryUseCase,
    private val bookmarkPlace: BookmarkPlaceUseCase,
    private val markupController: PdfMarkupController,
    private val findController: PdfFindController,
    private val printController: DocumentPrintController,
) : ViewModel() {
    /** The gallery of the open picture: one picture, or the sorted pictures around it to swipe through. */
    val galleryState: StateFlow<GalleryUiState> = gallery.state
    val gallerySort: StateFlow<GallerySort> = gallery.sort
    /** Quarter turns per picture (keyed by URI), so each picture keeps its own rotation while paging. */
    private val rotations = mutableStateMapOf<String, Int>()
    private var rememberJob: Job? = null

    /**
     * Turns the gallery to [index] (the pager settled there). The open file becomes that picture; nothing reloads.
     * Recents follow the picture on screen, written once the swipes settle rather than on every page.
     */
    fun selectGalleryPage(index: Int) {
        val file = gallery.select(index) ?: return
        saved[OPEN_URI] = file.uri.toString()
        rememberJob?.cancel()
        rememberJob = viewModelScope.launch {
            delay(REMEMBER_DELAY_MS)
            documents.remember(file)
        }
    }

    /** The screen-sized decode of gallery page [index]. Throws when the picture cannot be decoded. */
    suspend fun galleryPage(index: Int, maxEdge: Int): Bitmap? = gallery.bitmap(index, maxEdge)

    /** The animation of gallery page [index] (GIF, WebP, AVIF), or null for a still picture. */
    suspend fun galleryAnimation(index: Int, maxEdge: Int): AnimatedImageDrawable? = gallery.animation(index, maxEdge)

    /** The animation of a single picture opened without a gallery (GIF, WebP, AVIF), or null for a still. */
    suspend fun pictureAnimation(file: FileEntry, maxEdge: Int): AnimatedImageDrawable? = gallery.animationOf(file, maxEdge)

    fun setGallerySort(sort: GallerySort) = gallery.setSort(sort)

    fun rotationOf(uri: String): Int = rotations[uri] ?: 0

    /** Turns the picture at [uri] a quarter clockwise; the turn is kept for that picture. */
    fun rotate(uri: String) {
        rotations[uri] = (rotationOf(uri) + 90) % 360
    }

    /** Share original for the open file; false (shown as an error) when no app can take it. */
    fun share() {
        val ready = state.value as? ReaderState.Ready ?: return
        if (!sharing.share(ready.file)) printController.fail("No app on this phone can take this file.", "Can't share")
    }

    private val mutableState = MutableStateFlow<ReaderState?>(null)
    val state = mutableState.asStateFlow()

    /** Cell and slide editing for the open spreadsheet or presentation; see [StructuredEditSession]. */
    val structuredEdits = StructuredEditSession(structured, viewModelScope, ::showEditedPreview)

    /** Kinds of sensitive number in the open document, checked in the background after it settles. */
    val sensitive: StateFlow<SensitiveFindings?> = sensitiveScan(state.map { (it as? ReaderState.Ready)?.file })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val printRequests = printController.requests
    val printing = printController.status

    fun print() {
        val ready = state.value as? ReaderState.Ready ?: return
        printController.print(viewModelScope, ready.file, pdfPassword)
    }

    fun dismissPrintError() = printController.dismiss()

    private var readerJob: Job? = null
    private var summaryJob: Job? = null
    private var pdfPassword: String? = null
    /** Archives left by [openNested], innermost last; [popNested] returns to them. */
    private val nestedReturn = ArrayDeque<String>()
    val pdfFind = findController.state
    val markup = markupController.state
    /** The scroll viewer on screen (HTML or spreadsheet), registered by the viewer while composed. */
    // Main-thread only: viewers register from composition effects, the assistant reads on the main thread.
    private val scrollViewers = ArrayList<ViewerScroll>(2)

    fun open(uri: Uri, page: Int = 0, password: String? = null) {
        if (markup.value.saving) return
        discardMarkup()
        clearPdfSearch()
        pdfPages.clear()
        pdfPassword = null
        saved[OPEN_URI] = uri.toString()
        saved[OPEN_PAGE] = page
        startReader(ReaderState.Loading) {
            val opened = openDocument(uri, page, password)
            if (opened is ReaderState.Ready) {
                val preview = opened.preview
                if (preview is FilePreview.Pdf) {
                    pdfPassword = password
                    pdfPages.remember(preview)
                }
                documents.remember(opened.file)
                (preview as? FilePreview.Structured)?.let { structuredEdits.bind(opened.file.uri, opened.file.name, it) } ?: structuredEdits.close()
            }
            mutableState.value = opened
        }
    }

    fun unlock(password: String) {
        val pending = state.value as? ReaderState.Password ?: return
        open(pending.file.uri, pending.initialPage, password)
    }

    /** Opens an entry extracted from the archive now on screen; [popNested] goes back to that archive. */
    fun openNested(uri: Uri) {
        val current = saved.get<String>(OPEN_URI) ?: return
        nestedReturn.addLast(current)
        open(uri)
    }

    /** Returns to the archive that [openNested] left. False when nothing was left behind. */
    fun popNested(): Boolean {
        val previous = nestedReturn.removeLastOrNull() ?: return false
        open(Uri.parse(previous))
        return true
    }

    fun close() {
        if (markup.value.saving) return
        printController.cancel()
        structuredEdits.close()
        discardMarkup()
        pdfPassword = null
        clearPdfSearch()
        pdfPages.clear()
        readerJob?.cancel()
        summaryJob?.cancel()
        saved.remove<String>(OPEN_URI)
        saved.remove<Int>(OPEN_PAGE)
        nestedReturn.clear()
        mutableState.value = null
    }

    fun changePage(index: Int) {
        val ready = state.value as? ReaderState.Ready ?: return
        val pdf = ready.preview as? FilePreview.Pdf ?: return
        if (ready.turningPage || index !in 0 until pdf.pageCount || index == pdf.page) return
        startReader(ready.copy(turningPage = true, pageError = false), onError = ready.copy(pageError = true)) {
            val page = pdfPages.page(ready.file, index, pdfPassword)
            // The spread partner is read before the turn lands, so both pages appear together.
            val facing = if (ready.twoPage) pdfPartner(index, pdf.pageCount)?.let { pdfPages.page(ready.file, it, pdfPassword) } else null
            val active = mutableState.value as? ReaderState.Ready ?: return@startReader
            mutableState.value = active.copy(preview = page, facing = facing, turningPage = false, pageError = false)
            saved[OPEN_PAGE] = index
            pdfPages.prefetch(viewModelScope, ready.file, index, pdf.pageCount, ready.twoPage, pdfPassword)
        }
    }

    /** Two pages side by side, as pairs (0,1), (2,3)...: turns move by a pair. */
    fun setTwoPage(enabled: Boolean) {
        val ready = state.value as? ReaderState.Ready ?: return
        val pdf = ready.preview as? FilePreview.Pdf ?: return
        if (ready.turningPage || ready.twoPage == enabled) return
        val partner = if (enabled) pdfPartner(pdf.page, pdf.pageCount) else null
        mutableState.value = ready.copy(twoPage = enabled, facing = null)
        if (!enabled) {
            pdfPages.prefetch(viewModelScope, ready.file, pdf.page, pdf.pageCount, false, pdfPassword)
            return
        }
        viewModelScope.launch {
            val facing = partner?.let {
                try {
                    pdfPages.page(ready.file, it, pdfPassword)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("XfilesReader", "Spread page could not be read", error)
                    null
                }
            }
            val current = mutableState.value as? ReaderState.Ready ?: return@launch
            if (current.file.uri == ready.file.uri && current.twoPage && (current.preview as? FilePreview.Pdf)?.page == pdf.page) {
                mutableState.value = current.copy(facing = facing)
            }
        }
        pdfPages.prefetch(viewModelScope, ready.file, pdf.page, pdf.pageCount, true, pdfPassword)
    }

    /**
     * One page of the open PDF at reading size, through the bitmap cache, for the continuous view. Null when the
     * open file is not a PDF or the page cannot be rendered. Neighbours warm up on page changes (see changePage).
     */
    suspend fun pagePreview(index: Int): FilePreview.Pdf? {
        val ready = state.value as? ReaderState.Ready ?: return null
        if (ready.preview !is FilePreview.Pdf) return null
        return try {
            pdfPages.page(ready.file, index, pdfPassword)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("XfilesReader", "PDF page could not be rendered", error)
            null
        }
    }

    /** A page for the grid: small, cached and read off the main thread. Null when none can be read. */
    suspend fun pdfThumbnail(index: Int): Bitmap? {
        val ready = state.value as? ReaderState.Ready ?: return null
        if (ready.preview !is FilePreview.Pdf) return null
        return pdfPages.thumbnail(ready.file, index, pdfPassword)
    }

    /** Character geometry of PDF page [index] of the open file, for text selection; null when it cannot be read. */
    suspend fun pdfText(index: Int): PdfPageText? {
        val ready = state.value as? ReaderState.Ready ?: return null
        if (ready.preview !is FilePreview.Pdf) return null
        return pdfPages.text(ready.file, index, pdfPassword)
    }

    fun findPdf(query: String) {
        val active = state.value as? ReaderState.Ready ?: run { findController.clear(); return }
        val pdf = active.preview as? FilePreview.Pdf
        if (pdf == null || query.isBlank() || !pdf.searchable) {
            findController.clear()
            return
        }
        findController.start(viewModelScope, active.file, query, pdfPassword) { page -> changePage(page) }
    }

    fun movePdfMatch(direction: Int) {
        val active = state.value as? ReaderState.Ready ?: return
        if (active.turningPage) return
        findController.move(direction) { page -> changePage(page) }
    }

    fun clearPdfSearch() = findController.clear()

    fun addMarkup(mark: PdfMarkup) = markupController.add(mark)

    fun undoMarkup() = markupController.undo()

    fun prepareMarkup() {
        val active = state.value as? ReaderState.Ready ?: return
        if (active.preview !is FilePreview.Pdf) return
        markupController.prepare(viewModelScope, active.file.uri)
    }

    fun saveMarkup(uri: Uri) = markupController.save(viewModelScope, uri)

    private fun discardMarkup() = markupController.discard()

    override fun onCleared() {
        markupController.release()
        super.onCleared()
    }

    fun toggleBookmark() {
        val ready = state.value as? ReaderState.Ready ?: return
        val pdf = ready.preview as? FilePreview.Pdf
        val password = pdfPassword
        viewModelScope.launch {
            // The place is best effort: a bookmark saves at once, and waits at most briefly for the page's text.
            val place = pdf?.let { bookmarkPlace(ready.file, it.page, password) }
            val bookmark = FileBookmark(ready.file, pdf?.page, place?.first, place?.second)
            val saved = documents.toggleBookmark(bookmark)
            val current = mutableState.value as? ReaderState.Ready ?: return@launch
            if (current.file.uri == ready.file.uri) {
                mutableState.value = current.copy(bookmarkError = !saved)
            }
        }
    }

    fun showSummary() {
        val ready = state.value as? ReaderState.Ready ?: return
        val shown = when (val preview = ready.preview) {
            is FilePreview.Text -> preview.content
            is FilePreview.Structured -> preview.document.plainText()
            is FilePreview.Html -> preview.text.content
            is FilePreview.Pdf -> null // Read below: text layer, or the pages by OCR.
            else -> return
        }
        if (shown != null && shown.isBlank()) return
        if (ready.summary != null) return
        mutableState.value = ready.copy(summary = SummaryState.Loading)
        val password = pdfPassword
        summaryJob = viewModelScope.launch {
            val result = try {
                summarize(ready.file, shown, password)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesReader", "Summary failed", error)
                SummaryState.Error
            } catch (error: LinkageError) {
                Log.e("XfilesReader", "Rust analysis library unavailable", error)
                SummaryState.Error
            }
            // The page may have turned while the summary was read; keep the current page.
            val current = mutableState.value as? ReaderState.Ready ?: return@launch
            if (current.file.uri == ready.file.uri) mutableState.value = current.copy(summary = result)
        }
    }

    fun dismissSummary() {
        summaryJob?.cancel()
        val ready = state.value as? ReaderState.Ready ?: return
        mutableState.value = ready.copy(summary = null)
    }

    /**
     * Registers the scroll viewer on screen; the returned function unregisters it, but only while it is still the
     * current one. The assistant reads [currentScrollViewer] when it runs a scroll command.
     */
    fun attachViewerScroll(scroll: ViewerScroll): () -> Unit {
        scrollViewers.add(scroll)
        return { scrollViewers.removeAll { it === scroll } }
    }

    /** The most recently shown scroll viewer still on screen, or null when the open file has none (pictures). */
    val currentScrollViewer: ViewerScroll?
        get() = scrollViewers.lastOrNull()

    private fun startReader(
        initial: ReaderState,
        onError: ReaderState? = null,
        operation: suspend () -> Unit,
    ) {
        readerJob?.cancel()
        summaryJob?.cancel()
        mutableState.value = initial
        readerJob = viewModelScope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesReader", "Document could not be read", error)
                mutableState.value = onError ?: ReaderState.ReadError
            } catch (error: LinkageError) {
                Log.e("XfilesReader", "Rust document library unavailable", error)
                mutableState.value = ReaderState.EngineUnavailable
            }
        }
    }

    // Last in the class: every property above is initialised before the file is reopened.
    init {
        // Before the restore below, so a restored picture starts its gallery too.
        gallery.attach(viewModelScope, mutableState)
        // The process can die while Xfiles sits in the background (common when another app opened the
        // file into its own task). The open file comes back instead of dropping to the library.
        saved.get<String>(OPEN_URI)?.let { open(Uri.parse(it), saved[OPEN_PAGE] ?: 0) }
    }

    /**
     * Shows [preview] of an office edit in place of the open file. Null restores the
     * original by reopening its URI. The file on disk is never changed by this.
     */
    fun showEditedPreview(preview: FilePreview?) {
        val ready = mutableState.value as? ReaderState.Ready ?: return
        if (preview == null) open(ready.file.uri) else mutableState.value = ready.copy(preview = preview)
    }

    private companion object {
        const val OPEN_URI = "reader.openUri"
        const val OPEN_PAGE = "reader.openPage"
        const val REMEMBER_DELAY_MS = 1_000L
    }
}
