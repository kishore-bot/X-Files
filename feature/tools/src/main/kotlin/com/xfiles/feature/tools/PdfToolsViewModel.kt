package com.xfiles.feature.tools

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Edit PDFs (false) or Create & convert (true). */
    fun showConvert(convert: Boolean) = mutableState.update { it.copy(convert = convert) }

    /** Fily's hand-off: [action] with [uris] already chosen, shown for the user to review and run. */
    fun prefill(action: PdfAction, uris: List<Uri>) {
        if (mutableState.value.busy) return
        mutableState.value.output?.file?.delete()
        mutableState.value = PdfToolsState(action = action)
        pick(uris)
    }

    fun selectAction(action: PdfAction) {
        val current = mutableState.value
        if (current.action == action || current.busy) return
        current.output?.file?.delete()
        mutableState.value = PdfToolsState(action = action, convert = current.convert)
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
        mutableState.value = PdfToolsState(convert = mutableState.value.convert)
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
    val convert: Boolean = false,
)
