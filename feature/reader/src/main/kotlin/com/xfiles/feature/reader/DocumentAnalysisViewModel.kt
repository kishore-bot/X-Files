package com.xfiles.feature.reader

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileBookmark
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfMatchBox
import com.xfiles.core.documents.PdfToolsRepository
import com.xfiles.core.documents.PreparedPdf
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.documents.DocumentAnalysis
import com.xfiles.documents.PdfMarkup
import com.xfiles.documents.structure.DocumentError
import com.xfiles.documents.structure.DocumentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import com.xfiles.core.documents.ConversionRepository
import com.xfiles.core.documents.UnprintableDocument
import com.xfiles.core.documents.toTidyFile
import com.xfiles.core.documents.DocumentSharing
import com.xfiles.documents.DocumentKind
import com.xfiles.tidy.SensitiveCheck
import com.xfiles.tidy.SensitiveFindings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentAnalysisViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val tools: PdfToolsRepository,
    private val conversions: ConversionRepository,
    private val sensitiveCheck: SensitiveCheck,
    private val sharing: DocumentSharing,
    private val saved: SavedStateHandle,
    private val gallery: GalleryController,
    private val structured: com.xfiles.core.documents.OfficeDocumentEditor,
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
        if (!sharing.share(ready.file)) mutablePrinting.value = PrintStatus.Failed("No app on this phone can take this file.", "Can't share")
    }

    private val mutableState = MutableStateFlow<ReaderState?>(null)
    val state = mutableState.asStateFlow()

    /** Cell and slide editing for the open spreadsheet or presentation; see [StructuredEditSession]. */
    val structuredEdits = StructuredEditSession(structured, viewModelScope, ::showEditedPreview)

    /**
     * Kinds of sensitive number in the open document, checked in the background after it settles.
     * transformLatest cancels the check for a document that was closed or replaced.
     */
    val sensitive: StateFlow<SensitiveFindings?> = state
        .map { (it as? ReaderState.Ready)?.file }
        .distinctUntilChanged()
        .transformLatest { file ->
            emit(null)
            if (file == null || file.kind !in setOf(DocumentKind.Pdf, DocumentKind.Document, DocumentKind.Text)) return@transformLatest
            delay(1_200) // Let the document draw first; opening is never slower.
            val found = try {
                sensitiveCheck.check(file.toTidyFile())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesReader", "Sensitive check failed", error)
                null
            }
            emit(found)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One-shot print hand-offs: the screen owns the Activity the system print UI needs. */
    private val printJobs = Channel<PrintJob>(Channel.BUFFERED)
    val printRequests = printJobs.receiveAsFlow()
    private val mutablePrinting = MutableStateFlow<PrintStatus>(PrintStatus.Idle)
    val printing = mutablePrinting.asStateFlow()
    private var printJob: Job? = null

    fun print() {
        val ready = state.value as? ReaderState.Ready ?: return
        if (printJob?.isActive == true) return
        printJob = viewModelScope.launch {
            mutablePrinting.value = PrintStatus.Preparing
            mutablePrinting.value = try {
                printJobs.send(PrintJob(conversions.printable(ready.file, pdfPassword), ready.file.name))
                PrintStatus.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UnprintableDocument) {
                PrintStatus.Failed(error.message.orEmpty())
            } catch (error: IllegalArgumentException) {
                PrintStatus.Failed(error.message ?: "This document can't be printed.")
            } catch (error: Exception) {
                Log.w("XfilesReader", "Print preparation failed", error)
                PrintStatus.Failed("This document could not be prepared for printing.")
            }
        }
    }

    fun dismissPrintError() {
        mutablePrinting.value = PrintStatus.Idle
    }

    private var readerJob: Job? = null
    private var summaryJob: Job? = null
    private var pdfSearchJob: Job? = null
    private var pdfPassword: String? = null
    /** Archives left by [openNested], innermost last; [popNested] returns to them. */
    private val nestedReturn = ArrayDeque<String>()
    /** Recently shown PDF pages (current, spread partner, neighbours to prefetch). Bounded; main-thread only. */
    private val pdfPageCache = object : LinkedHashMap<Int, FilePreview.Pdf>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FilePreview.Pdf>?): Boolean =
            size > PDF_CACHED_PAGES
    }
    private var prefetchJob: Job? = null
    private val mutablePdfFind = MutableStateFlow(PdfFindState())
    val pdfFind = mutablePdfFind.asStateFlow()
    private val mutableMarkup = MutableStateFlow(PdfMarkupState())
    val markup = mutableMarkup.asStateFlow()
    private var markupJob: Job? = null

    fun open(uri: Uri, page: Int = 0, password: String? = null) {
        if (markup.value.saving) return
        discardMarkup()
        clearPdfSearch()
        clearPdfPages()
        pdfPassword = null
        saved[OPEN_URI] = uri.toString()
        saved[OPEN_PAGE] = page
        startReader(ReaderState.Loading) {
            val file = documents.describe(uri)
            val preview = try {
                documents.preview(file, page, password)
            } catch (error: DocumentException) {
                val reason = error.error
                mutableState.value = when (reason) {
                    is DocumentError.PasswordRequired -> ReaderState.Password(file, page, incorrect = false)
                    is DocumentError.WrongPassword -> ReaderState.Password(file, page, incorrect = true)
                    is DocumentError.UnsupportedEncryption -> ReaderState.Failure("This file uses an encryption format Xfiles cannot open.")
                    is DocumentError.LimitExceeded -> ReaderState.Failure("This file exceeds the reader's size limit.")
                    is DocumentError.Unsupported -> ReaderState.Ready(file, FilePreview.Unsupported)
                    else -> ReaderState.ReadError
                }
                return@startReader
            }
            if (preview is FilePreview.Pdf) {
                pdfPassword = password
                pdfPageCache[preview.page] = preview
            }
            documents.remember(file)
            (preview as? FilePreview.Structured)?.let { structuredEdits.bind(file.uri, file.name, it) } ?: structuredEdits.close()
            mutableState.value = ReaderState.Ready(file, preview)
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
        printJob?.cancel()
        structuredEdits.close()
        mutablePrinting.value = PrintStatus.Idle
        discardMarkup()
        pdfPassword = null
        clearPdfSearch()
        clearPdfPages()
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
            val page = pdfPage(ready.file, index)
            // The spread partner is read before the turn lands, so both pages appear together.
            val facing = if (ready.twoPage) partnerOf(index, pdf.pageCount)?.let { pdfPage(ready.file, it) } else null
            val active = mutableState.value as? ReaderState.Ready ?: return@startReader
            mutableState.value = active.copy(preview = page, facing = facing, turningPage = false, pageError = false)
            saved[OPEN_PAGE] = index
            prefetchPdf(ready.file, index, pdf.pageCount, ready.twoPage)
        }
    }

    /** Two pages side by side, as pairs (0,1), (2,3)…: turns move by a pair. */
    fun setTwoPage(enabled: Boolean) {
        val ready = state.value as? ReaderState.Ready ?: return
        val pdf = ready.preview as? FilePreview.Pdf ?: return
        if (ready.turningPage || ready.twoPage == enabled) return
        val partner = if (enabled) partnerOf(pdf.page, pdf.pageCount) else null
        mutableState.value = ready.copy(twoPage = enabled, facing = null)
        if (!enabled) {
            prefetchPdf(ready.file, pdf.page, pdf.pageCount, false)
            return
        }
        viewModelScope.launch {
            val facing = partner?.let {
                try {
                    pdfPage(ready.file, it)
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
        prefetchPdf(ready.file, pdf.page, pdf.pageCount, true)
    }

    /** A page for the grid: small, cached and read off the main thread. Null when none can be read. */
    suspend fun pdfThumbnail(index: Int): Bitmap? {
        val ready = state.value as? ReaderState.Ready ?: return null
        if (ready.preview !is FilePreview.Pdf) return null
        return try {
            documents.pageThumbnail(ready.file, index, pdfPassword, GRID_THUMBNAIL_EDGE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("XfilesReader", "PDF thumbnail failed", error)
            null
        }
    }

    /** Page bitmap from the small cache, or rendered once and kept while recent. */
    private suspend fun pdfPage(file: FileEntry, index: Int): FilePreview.Pdf {
        pdfPageCache[index]?.let { return it }
        return documents.page(file, index, pdfPassword).also { pdfPageCache[index] = it }
    }

    /** Warms the neighbours of what is shown, so the next turn is already rendered. Failures are silent. */
    private fun prefetchPdf(file: FileEntry, page: Int, count: Int, twoPage: Boolean) {
        prefetchJob?.cancel()
        val targets = if (twoPage) {
            val start = page - page % 2
            listOf(start + 2, start + 3, start - 2, start - 1)
        } else {
            listOf(page + 1, page - 1)
        }
        prefetchJob = viewModelScope.launch {
            for (index in targets) {
                if (index !in 0 until count || pdfPageCache.containsKey(index)) continue
                try {
                    pdfPage(file, index)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("XfilesReader", "PDF prefetch skipped", error)
                }
            }
        }
    }

    private fun clearPdfPages() {
        prefetchJob?.cancel()
        prefetchJob = null
        pdfPageCache.clear()
    }

    fun findPdf(query: String) {
        clearPdfSearch()
        val active = state.value as? ReaderState.Ready ?: return
        val pdf = active.preview as? FilePreview.Pdf ?: return
        if (query.isBlank() || !pdf.searchable) return
        val password = pdfPassword
        mutablePdfFind.value = PdfFindState(loading = true)
        pdfSearchJob = viewModelScope.launch {
            delay(180)
            try {
                val result = documents.searchPdf(active.file, query, password)
                val matches = result.pages.flatMap { page ->
                    page.matches.map { boxes -> PdfFindMatch(page.page, boxes) }
                }
                mutablePdfFind.value = PdfFindState(matches = matches, truncated = result.truncated)
                matches.firstOrNull()?.let { changePage(it.page) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesReader", "PDF search failed", error)
                mutablePdfFind.value = PdfFindState(error = true)
            }
        }
    }

    fun movePdfMatch(direction: Int) {
        val current = mutablePdfFind.value
        val active = state.value as? ReaderState.Ready ?: return
        if (active.turningPage) return
        val index = current.selected + direction
        val match = current.matches.getOrNull(index) ?: return
        mutablePdfFind.value = current.copy(selected = index)
        changePage(match.page)
    }

    fun clearPdfSearch() {
        pdfSearchJob?.cancel()
        pdfSearchJob = null
        mutablePdfFind.value = PdfFindState()
    }

    fun addMarkup(mark: PdfMarkup) {
        val current = markup.value
        if (current.working) return
        if (current.marks.size >= 256) {
            mutableMarkup.value = current.copy(error = "Save the current annotations before adding more.")
            return
        }
        current.output?.file?.delete()
        mutableMarkup.value = PdfMarkupState(marks = current.marks + mark)
    }

    fun undoMarkup() {
        val current = markup.value
        if (current.working || current.marks.isEmpty()) return
        current.output?.file?.delete()
        mutableMarkup.value = PdfMarkupState(marks = current.marks.dropLast(1))
    }

    fun prepareMarkup() {
        val active = state.value as? ReaderState.Ready ?: return
        if (active.preview !is FilePreview.Pdf) return
        val current = markup.value
        if (current.working || current.marks.isEmpty() || current.output != null) return
        mutableMarkup.value = current.copy(working = true, error = null)
        markupJob = viewModelScope.launch {
            try {
                val output = tools.annotate(active.file.uri, current.marks)
                mutableMarkup.value = current.copy(output = output, error = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                mutableMarkup.value = current.copy(error = error.message ?: "These annotations could not be prepared.")
            } catch (error: Exception) {
                Log.w("XfilesReader", "Could not annotate PDF", error)
                mutableMarkup.value = current.copy(error = "These annotations could not be saved to a PDF copy.")
            } catch (error: LinkageError) {
                Log.e("XfilesReader", "Rust PDF markup unavailable", error)
                mutableMarkup.value = current.copy(error = "The PDF engine is unavailable on this device.")
            }
        }
    }

    fun saveMarkup(uri: Uri) {
        val current = markup.value
        val output = current.output ?: return
        if (current.working) return
        mutableMarkup.value = current.copy(working = true, saving = true, error = null)
        markupJob = viewModelScope.launch {
            try {
                tools.save(output, uri)
                output.file.delete()
                mutableMarkup.value = PdfMarkupState(saved = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesReader", "Could not export annotated PDF", error)
                mutableMarkup.value = current.copy(error = "The annotated PDF could not be saved here.")
            }
        }
    }

    private fun discardMarkup() {
        markupJob?.cancel()
        markup.value.output?.file?.delete()
        mutableMarkup.value = PdfMarkupState()
    }

    override fun onCleared() {
        markup.value.output?.file?.delete()
        super.onCleared()
    }

    fun toggleBookmark() {
        val ready = state.value as? ReaderState.Ready ?: return
        val bookmark = FileBookmark(ready.file, (ready.preview as? FilePreview.Pdf)?.page)
        viewModelScope.launch {
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
                val text = shown ?: documents.extractText(ready.file, 32_000, password)
                if (text.isBlank()) SummaryState.Error else SummaryState.Ready(documents.summary(text))
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
        const val PDF_CACHED_PAGES = 6
        const val GRID_THUMBNAIL_EDGE = 240
        const val REMEMBER_DELAY_MS = 1_000L
    }

    /** Index of the page that shares a spread with [page] (pairs 0-1, 2-3…), or null when there is none. */
    private fun partnerOf(page: Int, count: Int): Int? =
        (if (page % 2 == 0) page + 1 else page - 1).takeIf { it in 0 until count }
}

sealed interface ReaderState {
    data object Loading : ReaderState
    data object ReadError : ReaderState
    data object EngineUnavailable : ReaderState
    data class Password(val file: FileEntry, val initialPage: Int, val incorrect: Boolean) : ReaderState
    data class Failure(val message: String) : ReaderState

    data class Ready(
        val file: FileEntry,
        val preview: FilePreview,
        val turningPage: Boolean = false,
        val pageError: Boolean = false,
        val bookmarkError: Boolean = false,
        val summary: SummaryState? = null,
        /** Two-page view: the spread's other page (null for single view, or when there is none). */
        val twoPage: Boolean = false,
        val facing: FilePreview.Pdf? = null,
    ) : ReaderState
}

sealed interface SummaryState {
    data object Loading : SummaryState
    data object Error : SummaryState
    data class Ready(val analysis: DocumentAnalysis) : SummaryState
}

data class PdfFindMatch(val page: Int, val boxes: List<PdfMatchBox>)
data class PdfFindState(
    val matches: List<PdfFindMatch> = emptyList(),
    val selected: Int = 0,
    val loading: Boolean = false,
    val truncated: Boolean = false,
    val error: Boolean = false,
)

data class PdfMarkupState(
    val marks: List<PdfMarkup> = emptyList(),
    val output: PreparedPdf? = null,
    val working: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)
