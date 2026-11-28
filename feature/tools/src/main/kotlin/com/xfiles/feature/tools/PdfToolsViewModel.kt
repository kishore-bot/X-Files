package com.xfiles.feature.tools

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.PdfAction
import com.xfiles.core.documents.PdfPageRangeError
import com.xfiles.core.documents.PdfToolOptions
import com.xfiles.core.documents.PdfToolPasswordError
import com.xfiles.core.documents.PdfToolsRepository
import com.xfiles.core.documents.PreparedPdf
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.PdfProtectionChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.xfiles.core.documents.AndroidPdfDocument
import com.xfiles.core.documents.OpenedPdf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PdfToolsViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val tools: PdfToolsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PdfToolsState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null

    /** The tool on screen, or null for the hub. Kept apart from [state] so resetting a tool's form never navigates. */
    private val mutableOpen = MutableStateFlow<OpenTool?>(null)
    val open: StateFlow<OpenTool?> = mutableOpen.asStateFlow()

    /** A page list Fily filled in for the open tool's page field; the screen takes it once. */
    private val mutableSuggestedPages = MutableStateFlow<String?>(null)
    val suggestedPages: StateFlow<String?> = mutableSuggestedPages.asStateFlow()

    fun consumeSuggestedPages() {
        mutableSuggestedPages.value = null
    }

    /** True when the open tool was picked in the PDF tools grid: back returns there. Otherwise back leaves Tools. */
    var openedFromHub = false
        private set

    fun open(tool: OpenTool?, fromHub: Boolean = false) {
        if (tool is OpenTool.Pdf) selectAction(tool.action)
        openedFromHub = fromHub
        mutableOpen.value = tool
    }

    /** Fily's hand-off: [action] with [uris] already chosen, shown for the user to review and run. */
    fun prefill(action: PdfAction, uris: List<Uri>, pages: String? = null): Boolean {
        if (mutableState.value.busy) return false
        mutableState.value.output?.file?.delete()
        mutableState.value = PdfToolsState(action = action)
        // Only page-list characters: the user still sees and edits the range before running.
        mutableSuggestedPages.value = pages?.filter { it.isDigit() || it in ", -" }?.trim()?.take(MAX_SUGGESTED_PAGES)
            ?.takeIf(String::isNotEmpty)
        openedFromHub = false
        mutableOpen.value = OpenTool.Pdf(action)
        pick(uris)
        return true
    }

    fun selectAction(action: PdfAction) {
        val current = mutableState.value
        if (current.action == action || current.busy) return
        current.output?.file?.delete()
        mutableState.value = PdfToolsState(action = action)
    }

    fun pick(uris: List<Uri>) {
        if (uris.isEmpty() || mutableState.value.busy) return
        work = viewModelScope.launch {
            try {
                val current = mutableState.value
                val limit = current.action.inputCount.last
                mutableState.value = current.copy(busy = true, error = null)
                val selection = uris.distinct()
                require(selection.size <= limit) { "Select up to $limit PDFs." }
                val files = selection.map { documents.describe(it) }
                require(files.all { it.kind == DocumentKind.Pdf }) { "Choose PDF files." }
                current.output?.file?.delete()
                mutableState.value = PdfToolsState(action = current.action, inputs = files)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                mutableState.update { it.copy(busy = false, error = error.message) }
            } catch (error: Exception) {
                Log.w("XfilesTools", "Could not read selected files", error)
                mutableState.update { it.copy(busy = false, error = "A selected file could not be opened.") }
            }
        }
    }

    fun move(index: Int, to: Int) {
        val current = mutableState.value
        if (current.busy || current.output != null || index !in current.inputs.indices || to !in current.inputs.indices) return
        val reordered = current.inputs.toMutableList()
        reordered.add(to, reordered.removeAt(index))
        mutableState.value = current.copy(inputs = reordered)
    }

    fun prepare(options: PdfToolOptions) {
        val current = mutableState.value
        if (current.busy || current.output != null || current.inputs.isEmpty()) return
        if (current.inputs.size !in current.action.inputCount) {
            mutableState.value = current.copy(error = "Select ${current.action.inputCount.first} PDFs to continue.")
            return
        }
        work = viewModelScope.launch {
            mutableState.value = current.copy(busy = true, running = true, error = null, saved = false)
            try {
                val output = tools.run(current.action, current.inputs.map { it.uri }, options) { done, total ->
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    mutableState.update { it.copy(progress = fraction) }
                }
                mutableState.value = current.copy(output = output, error = null, saved = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PdfPageRangeError) {
                val message = when (current.action) {
                    PdfAction.Insert -> "Choose an insertion position from 1 to ${error.pageCount}."
                    PdfAction.Delete -> "Choose pages from 1 to ${error.pageCount} and keep at least one page."
                    else -> "Choose up to 500 pages from 1 to ${error.pageCount}."
                }
                mutableState.value = current.copy(error = message)
            } catch (error: PdfToolPasswordError) {
                val message = if (error.wrong) "That password did not unlock the source PDF." else "Enter the source PDF's password. This operation may need the owner password."
                mutableState.value = current.copy(error = message)
            } catch (error: IllegalArgumentException) {
                mutableState.value = current.copy(error = error.message ?: "Check the tool options.")
            } catch (error: Exception) {
                Log.w("XfilesTools", "PDF tool failed", error)
                mutableState.value = current.copy(error = "The PDF could not be prepared. Check that the PDFs are readable and together under 64 MB.")
            } catch (error: LinkageError) {
                Log.e("XfilesTools", "Rust document library unavailable", error)
                mutableState.value = current.copy(error = "The PDF engine is unavailable on this device.")
            }
        }
    }

    fun save(destination: Uri) {
        val current = mutableState.value
        val output = current.output ?: return
        if (current.busy) return
        work = viewModelScope.launch {
            mutableState.value = current.copy(busy = true, saving = true, error = null)
            try {
                tools.save(output, destination)
                output.file.delete()
                mutableState.value = current.copy(output = null, saved = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesTools", "Could not export PDF", error)
                mutableState.value = current.copy(error = "The PDF could not be saved here. Choose another location.")
            }
        }
    }

    fun cancel() {
        val current = mutableState.value
        if (current.saving) return
        work?.cancel()
        mutableState.value = current.copy(busy = false, running = false, progress = null)
    }

    fun close() {
        if (mutableState.value.saving) return
        cancel()
        mutableState.value.output?.file?.delete()
        mutableState.value = PdfToolsState()
    }

    override fun onCleared() {
        mutableState.value.output?.file?.delete()
        super.onCleared()
    }
}

data class PdfToolsState(
    val action: PdfAction = PdfAction.Merge,
    val inputs: List<FileEntry> = emptyList(),
    val output: PreparedPdf? = null,
    val busy: Boolean = false,
    val running: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

/** One tool of the Tools hub: a page-range PDF action, a PDF tool with its own screen, or a create/convert tool. */
sealed interface OpenTool {
    data class Pdf(val action: PdfAction) : OpenTool
    data class Dedicated(val tool: PdfDedicatedTool) : OpenTool
    data class Convert(val tool: ConvertTool) : OpenTool
}

/** What a dedicated single-PDF tool shows about its source and its latest run. */
data class PdfToolFrame(
    val name: String = "",
    val pageCount: Int = 0,
    val signed: Boolean = false,
    val archival: Boolean = false,
    val opening: Boolean = false,
    val needsPassword: Boolean = false,
    val busy: Boolean = false,
    val running: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    val outputs: List<PreparedPdf> = emptyList(),
) {
    val isOpen: Boolean get() = pageCount > 0
}

/**
 * Lifecycle shared by the dedicated tools (Reorder, Split, Compress, Watermark, Sign).
 * One source is copied into the workspace on open and released on close. Each run
 * is a single cancellable job on the repository's io lane, and its outputs wait
 * until the user saves or closes. A new run or a new source deletes the old outputs.
 */
abstract class PdfSingleFileToolViewModel<T>(
    initialTool: T,
    protected val tools: PdfToolsRepository,
    protected val documents: DocumentRepository,
    protected val pdf: AndroidPdfDocument,
) : ViewModel() {
    private val mutableTool = MutableStateFlow(initialTool)
    val tool: StateFlow<T> = mutableTool.asStateFlow()
    private val mutableFrame = MutableStateFlow(PdfToolFrame())
    val frame: StateFlow<PdfToolFrame> = mutableFrame.asStateFlow()
    private val mutableProtection = MutableStateFlow(PdfProtectionChoice.SameAsOriginal)
    /** How a protected source is protected again on the result. Same as original by default. */
    val protection: StateFlow<PdfProtectionChoice> = mutableProtection.asStateFlow()
    protected val protectionChoice: PdfProtectionChoice get() = mutableProtection.value

    fun setProtection(choice: PdfProtectionChoice) = mutableProtection.update { choice }
    protected var source: OpenedPdf? = null
    private var work: Job? = null

    protected fun setTool(value: T) = mutableTool.update { value }

    protected fun mutateFrame(transform: (PdfToolFrame) -> PdfToolFrame) = mutableFrame.update(transform)

    /** Called once a source is open, so subclasses can seed their own state. */
    protected open fun onOpened(opened: OpenedPdf) {}

    /** Called when the source is released, so subclasses can clear theirs. */
    protected open fun onReleased() {}

    protected val openedSource: OpenedPdf? get() = source

    fun open(uri: Uri, password: String? = null) {
        if (mutableFrame.value.busy) return
        release()
        work = viewModelScope.launch {
            mutateFrame { PdfToolFrame(opening = true, busy = true) }
            try {
                val name = documents.describe(uri).name
                val opened = tools.open(uri, password)
                source = opened
                onOpened(opened)
                mutateFrame {
                    PdfToolFrame(
                        name = name,
                        pageCount = opened.pageCount,
                        signed = opened.signed,
                        archival = opened.archival,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PdfToolPasswordError) {
                val message = if (error.wrong) "That password did not unlock this PDF." else "This PDF is password protected. Enter its password."
                mutateFrame { it.copy(opening = false, busy = false, needsPassword = true, error = message) }
            } catch (error: LinkageError) {
                Log.e("XfilesTools", "Rust document library unavailable", error)
                mutateFrame { it.copy(opening = false, busy = false, error = "The PDF engine is unavailable on this device.") }
            } catch (error: Exception) {
                Log.w("XfilesTools", "Could not open PDF for a tool", error)
                mutateFrame { it.copy(opening = false, busy = false, error = "This PDF could not be opened.") }
            }
        }
    }

    /** Runs [block] on the source. Its outputs wait for saving. */
    protected fun runTool(block: suspend (OpenedPdf, (Int, Int) -> Unit) -> List<PreparedPdf>) {
        val opened = source ?: return
        if (mutableFrame.value.busy) return
        mutableFrame.value.outputs.forEach { it.file.delete() }
        work = viewModelScope.launch {
            mutateFrame { it.copy(busy = true, running = true, progress = null, error = null, saved = false, outputs = emptyList()) }
            try {
                val outputs = block(opened) { done, total ->
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    mutateFrame { it.copy(progress = fraction) }
                }
                mutateFrame { it.copy(busy = false, running = false, progress = null, outputs = outputs) }
            } catch (cancelled: CancellationException) {
                mutateFrame { it.copy(busy = false, running = false, progress = null) }
                throw cancelled
            } catch (error: PdfPageRangeError) {
                fail("Choose pages from 1 to ${error.pageCount}.")
            } catch (error: PdfToolPasswordError) {
                fail("This PDF needs its password. Enter the password and try again.")
            } catch (error: IllegalArgumentException) {
                fail(error.message ?: "Check the options and try again.")
            } catch (error: LinkageError) {
                Log.e("XfilesTools", "Rust document library unavailable", error)
                fail("The PDF engine is unavailable on this device.")
            } catch (error: Exception) {
                Log.w("XfilesTools", "PDF tool failed", error)
                fail("The PDF could not be prepared. Check that it is readable.")
            }
        }
    }

    private fun fail(message: String) = mutateFrame { it.copy(busy = false, running = false, progress = null, error = message) }

    /** Saves the single output of the last run to [destination] and deletes the workspace copy. */
    fun save(destination: Uri) {
        val output = mutableFrame.value.outputs.singleOrNull() ?: return
        if (mutableFrame.value.busy) return
        work = viewModelScope.launch {
            mutateFrame { it.copy(busy = true, saving = true, error = null) }
            try {
                tools.save(output, destination)
                output.file.delete()
                mutateFrame { it.copy(busy = false, saving = false, outputs = emptyList(), saved = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesTools", "Could not export PDF", error)
                mutateFrame { it.copy(busy = false, saving = false, error = "The PDF could not be saved here. Choose another location.") }
            }
        }
    }

    /**
     * Writes every current output through [block] (for example one document per part),
     * then deletes the workspace copies. Failure keeps the outputs so the user can retry.
     */
    protected fun persist(block: suspend (List<PreparedPdf>) -> Unit) {
        val outputs = mutableFrame.value.outputs
        if (outputs.isEmpty() || mutableFrame.value.busy) return
        work = viewModelScope.launch {
            mutateFrame { it.copy(busy = true, saving = true, error = null) }
            try {
                block(outputs)
                outputs.forEach { it.file.delete() }
                mutateFrame { it.copy(busy = false, saving = false, outputs = emptyList(), saved = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesTools", "Could not export PDF parts", error)
                mutateFrame { it.copy(busy = false, saving = false, error = "The files could not be saved in that folder. Choose another folder.") }
            }
        }
    }

    fun cancel() {
        if (mutableFrame.value.saving) return
        work?.cancel()
        mutateFrame { it.copy(busy = false, running = false, progress = null) }
    }

    /** Renders one page for previews. Cached by the document. */
    protected suspend fun thumbnail(page: Int, maxEdge: Int = PREVIEW_EDGE): Bitmap {
        val opened = source ?: throw IllegalStateException("No PDF is open")
        return pdf.thumbnail(opened.uri, page, opened.password, maxEdge)
    }

    /** The first page as a small card thumbnail, for the source step of every dedicated tool. */
    suspend fun firstPreview(): Bitmap = thumbnail(0, SOURCE_PREVIEW_EDGE)

    fun release() {
        work?.cancel()
        mutableFrame.value.outputs.forEach { it.file.delete() }
        source?.let(tools::release)
        source = null
        onReleased()
        mutableFrame.value = PdfToolFrame()
    }

    override fun onCleared() {
        mutableFrame.value.outputs.forEach { it.file.delete() }
        source?.let(tools::release)
        super.onCleared()
    }

    companion object {
        const val PREVIEW_EDGE = 1024
        private const val MAX_SUGGESTED_PAGES = 200
        const val SOURCE_PREVIEW_EDGE = 360
    }
}
