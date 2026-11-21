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
import android.net.Uri
import android.util.Log
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
) : ViewModel() {
    /** Share original for the open file; false (shown as an error) when no app can take it. */
    fun share() {
        val ready = state.value as? ReaderState.Ready ?: return
        if (!sharing.share(ready.file)) mutablePrinting.value = PrintStatus.Failed("No app on this phone can take this file.", "Can't share")
    }

    private val mutableState = MutableStateFlow<ReaderState?>(null)
    val state = mutableState.asStateFlow()

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
    private val mutablePdfFind = MutableStateFlow(PdfFindState())
    val pdfFind = mutablePdfFind.asStateFlow()
    private val mutableMarkup = MutableStateFlow(PdfMarkupState())
    val markup = mutableMarkup.asStateFlow()
    private var markupJob: Job? = null

    fun open(uri: Uri, page: Int = 0, password: String? = null) {
        if (markup.value.saving) return
        discardMarkup()
        clearPdfSearch()
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
            if (preview is FilePreview.Pdf) pdfPassword = password
            documents.remember(file)
            mutableState.value = ReaderState.Ready(file, preview)
        }
    }

    fun unlock(password: String) {
        val pending = state.value as? ReaderState.Password ?: return
        open(pending.file.uri, pending.initialPage, password)
    }

    fun close() {
        if (markup.value.saving) return
        printJob?.cancel()
        mutablePrinting.value = PrintStatus.Idle
        discardMarkup()
        pdfPassword = null
        clearPdfSearch()
        readerJob?.cancel()
        summaryJob?.cancel()
        saved.remove<String>(OPEN_URI)
        saved.remove<Int>(OPEN_PAGE)
        mutableState.value = null
    }

    fun changePage(index: Int) {
        val ready = state.value as? ReaderState.Ready ?: return
        val pdf = ready.preview as? FilePreview.Pdf ?: return
        if (ready.turningPage || index !in 0 until pdf.pageCount || index == pdf.page) return
        startReader(ready.copy(turningPage = true, pageError = false), onError = ready.copy(pageError = true)) {
            val page = documents.page(ready.file, index, pdfPassword)
            val active = mutableState.value as? ReaderState.Ready ?: return@startReader
            mutableState.value = active.copy(preview = page, turningPage = false, pageError = false)
            saved[OPEN_PAGE] = index
        }
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
        // The process can die while Xfiles sits in the background (common when another app opened the
        // file into its own task). The open file comes back instead of dropping to the library.
        saved.get<String>(OPEN_URI)?.let { open(Uri.parse(it), saved[OPEN_PAGE] ?: 0) }
    }

    private companion object {
        const val OPEN_URI = "reader.openUri"
        const val OPEN_PAGE = "reader.openPage"
    }
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
