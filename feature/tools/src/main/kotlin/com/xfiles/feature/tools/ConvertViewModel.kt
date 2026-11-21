package com.xfiles.feature.tools

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.Conversion
import com.xfiles.core.documents.ConversionRepository
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.PdfPaper
import com.xfiles.core.documents.PdfToolPasswordError
import com.xfiles.core.documents.PdfPageRangeError
import com.xfiles.core.documents.PreparedPdf
import com.xfiles.core.documents.TextLayerOutcome
import com.xfiles.core.documents.TextRecognitionRepository
import com.xfiles.core.documents.ScanFilter
import com.xfiles.core.documents.ScanProcessing
import com.xfiles.scan.PageQuad
import com.xfiles.documents.DocumentKind
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Create tools (pictures, camera, text) and PDF conversions, in the order of the original Tools sheet. */
enum class ConvertTool(val label: String, val input: ToolInput) {
    PicturesToPdf("Pictures to PDF", ToolInput.Pictures),
    ScanToPdf("Scan to PDF", ToolInput.Camera),
    TextToPdf("Text to PDF", ToolInput.Typing),
    PictureToText("Picture to text", ToolInput.Picture),
    AddPictures("Add pictures to PDF", ToolInput.PdfAndPictures),
    PdfToPictures("PDF to pictures", ToolInput.Pdf),
    PdfToText("PDF to text", ToolInput.Pdf),
    PdfToZip("PDF to ZIP", ToolInput.Pdf),
}

enum class ToolInput { Pictures, Camera, Typing, Picture, Pdf, PdfAndPictures }

sealed interface ConvertOutput {
    /** [notice] says how far the searchable text layer got, when it was requested. */
    data class Pdf(val pdf: PreparedPdf, val suggestedName: String, val notice: String? = null) : ConvertOutput
    data class Text(val text: String, val suggestedName: String) : ConvertOutput
    data class Done(val message: String) : ConvertOutput
}

data class ConvertState(
    val tool: ConvertTool = ConvertTool.PicturesToPdf,
    val pictures: List<Uri> = emptyList(),
    val pdf: FileEntry? = null,
    val paper: PdfPaper? = PdfPaper.A4,
    val busy: Boolean = false,
    val progress: Float? = null,
    val output: ConvertOutput? = null,
    val error: String? = null,
    /** Adds an invisible, searchable text layer (OCR) to Pictures to PDF and Scan to PDF. */
    val searchable: Boolean = false,
    /** Text Fily drafted for Text to PDF, handed to the text field once. */
    val draft: String? = null,
    /** Set by the home-screen widget: the screen opens the camera once, then clears it. */
    val openScanner: Boolean = false,
)

@HiltViewModel
class ConvertViewModel @Inject constructor(
    private val application: Application,
    private val documents: DocumentRepository,
    private val conversions: ConversionRepository,
    private val recognition: TextRecognitionRepository,
    val scanProcessing: ScanProcessing,
) : ViewModel() {
    /**
     * One scanned page: straightened, filtered and encoded off the main thread, then added in order.
     * Pages finish on one lane, so they stay in the order they were shot.
     */
    fun addScan(photo: File, outline: PageQuad?, filter: ScanFilter) {
        viewModelScope.launch(scanLane) {
            try {
                val page = scanProcessing.finishPage(photo, outline, filter)
                var accepted = false
                // A page shot while the list was one short of the cap still lands only if it fits.
                mutableState.update { state ->
                    if (state.pictures.size < ScanProcessing.MAX_SCAN_PAGES) {
                        accepted = true
                        state.copy(pictures = state.pictures + page, output = null, error = null)
                    } else {
                        state
                    }
                }
                if (!accepted) {
                    File(page.path.orEmpty()).delete()
                    fail(SCAN_LIMIT_MESSAGE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesConvert", "Scanned page failed", error)
                fail("That page could not be processed. Try again.")
            }
        }
    }

    /** Turns the searchable-text option on or off for Pictures to PDF and Scan to PDF. Kept across tools. */
    fun setSearchable(on: Boolean) = mutableState.update { it.copy(searchable = on) }

    private val scanLane = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    private companion object {
        const val MAX_PICTURES = 100
        const val SCAN_LIMIT_MESSAGE = "This scan has ${ScanProcessing.MAX_SCAN_PAGES} pages, the most one PDF holds. Save it, then start another."
    }

    private val mutableState = MutableStateFlow(ConvertState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private val scans = File(application.cacheDir, "scans")

    fun select(tool: ConvertTool) {
        if (state.value.busy || state.value.tool == tool) return
        discardOutput()
        mutableState.value = ConvertState(tool = tool, searchable = state.value.searchable)
    }

    /** Fily's hand-off: [tool] with its PDF or drafted text filled in, waiting for the user to run it. */
    fun prefill(tool: ConvertTool, pdf: Uri? = null, draft: String? = null) {
        if (state.value.busy) return
        discardOutput()
        mutableState.value = ConvertState(tool = tool, draft = draft)
        pdf?.let(::pickPdf)
    }

    /** The screen took the drafted text into its field. */
    fun draftConsumed() = mutableState.update { it.copy(draft = null) }

    /** Widget hand-off: Scan to PDF with the camera opened once. Keeps pages already scanned. */
    fun openScanner() {
        if (state.value.busy) return
        if (state.value.tool != ConvertTool.ScanToPdf) select(ConvertTool.ScanToPdf)
        mutableState.update { it.copy(openScanner = true) }
    }

    /** The screen opened the camera for [openScanner]. */
    fun scannerOpened() = mutableState.update { it.copy(openScanner = false) }

    fun setPaper(paper: PdfPaper?) = mutableState.update { it.copy(paper = paper) }

    fun addPictures(uris: List<Uri>) {
        if (uris.isEmpty()) return
        discardOutput()
        mutableState.update {
            val pages = when (it.tool.input) {
                ToolInput.Picture -> uris.take(1)
                ToolInput.Camera -> (it.pictures + uris).distinct().take(ScanProcessing.MAX_SCAN_PAGES)
                else -> (it.pictures + uris).distinct().take(MAX_PICTURES)
            }
            it.copy(pictures = pages, output = null, error = null)
        }
    }

    fun removePicture(uri: Uri) = mutableState.update { it.copy(pictures = it.pictures - uri) }


    fun pickPdf(uri: Uri) = launch("The PDF could not be opened.") {
        val file = documents.describe(uri)
        require(file.kind == DocumentKind.Pdf) { "Choose a PDF file." }
        discardOutput()
        mutableState.update { it.copy(pdf = file, output = null) }
    }

    /** Runs the selected tool. [target] is the folder picked for PDF to pictures, or the .zip for PDF to ZIP. */
    fun run(text: String = "", password: String? = null, target: Uri? = null) {
        val current = state.value
        when (current.tool) {
            ConvertTool.PicturesToPdf, ConvertTool.ScanToPdf -> collect(conversions.imagesToPdf(current.pictures, current.paper, current.searchable)) { composed ->
                ConvertOutput.Pdf(
                    composed.pdf,
                    if (current.tool == ConvertTool.ScanToPdf) "Scan.pdf" else "Pictures.pdf",
                    composed.textLayer?.let(::searchNotice),
                )
            }
            ConvertTool.TextToPdf -> launch("The text could not be laid out as a PDF.") {
                setOutput(ConvertOutput.Pdf(conversions.textToPdf(text, current.paper ?: PdfPaper.A4), "Text.pdf"))
            }
            ConvertTool.PictureToText -> launch("No text could be read from this picture.") {
                val picture = current.pictures.firstOrNull() ?: return@launch
                val read = recognition.recognize(picture)
                require(read.isNotBlank()) { "No text was found in this picture." }
                setOutput(ConvertOutput.Text(read, "Picture text.txt"))
            }
            ConvertTool.AddPictures -> {
                val pdf = current.pdf ?: return
                // [text] carries the optional "insert before page" number; blank appends at the end.
                val before = text.trim().takeIf(String::isNotEmpty)?.let { it.toIntOrNull() ?: return fail("Enter a page number, or leave it blank to add at the end.") }
                collect(conversions.addPicturesToPdf(pdf.uri, password, current.pictures, current.paper, before)) { result ->
                    ConvertOutput.Pdf(result, pdf.name.removeSuffix(".pdf") + " with pictures.pdf")
                }
            }
            ConvertTool.PdfToPictures -> {
                val pdf = current.pdf ?: return
                val folder = target ?: return
                collect(conversions.pdfToImages(pdf.uri, password, folder, pdf.name.removeSuffix(".pdf"))) { count ->
                    ConvertOutput.Done("$count ${if (count == 1) "picture" else "pictures"} saved.")
                }
            }
            ConvertTool.PdfToText -> launch("The PDF's text could not be read.") {
                val pdf = current.pdf ?: return@launch
                setOutput(ConvertOutput.Text(conversions.pdfToText(pdf), pdf.name.removeSuffix(".pdf") + ".txt"))
            }
            ConvertTool.PdfToZip -> launch("The ZIP could not be saved here. Choose another location.") {
                val pdf = current.pdf ?: return@launch
                conversions.zip(pdf, target ?: return@launch)
                setOutput(ConvertOutput.Done("ZIP saved."))
            }
        }
    }

    fun savePdf(destination: Uri) = launch("The PDF could not be saved here. Choose another location.") {
        val output = state.value.output as? ConvertOutput.Pdf ?: return@launch
        conversions.save(output.pdf, destination)
        setOutput(ConvertOutput.Done("PDF saved."))
    }

    fun saveText(destination: Uri) = launch("The text could not be saved here. Choose another location.") {
        val output = state.value.output as? ConvertOutput.Text ?: return@launch
        conversions.saveText(destination, output.text)
        setOutput(ConvertOutput.Done("Text saved."))
    }

    fun cancel() {
        work?.cancel()
        mutableState.update { it.copy(busy = false, progress = null) }
    }

    fun close() {
        cancel()
        discardOutput()
        scans.listFiles()?.forEach(File::delete)
        mutableState.value = ConvertState()
    }

    override fun onCleared() {
        discardOutput()
        scans.listFiles()?.forEach(File::delete)
        super.onCleared()
    }

    /** Collects a [Conversion] flow into progress, then maps its single result to an output. */
    private fun <T> collect(flow: Flow<Conversion<T>>, toOutput: (T) -> ConvertOutput) =
        launch("The conversion failed. Check that the files are readable and try again.") {
            flow.collect { event ->
                when (event) {
                    is Conversion.Progress -> mutableState.update {
                        it.copy(progress = event.total?.let { total -> event.done.toFloat() / total.coerceAtLeast(1) })
                    }
                    is Conversion.Done -> setOutput(toOutput(event.result))
                }
            }
        }

    private fun launch(failure: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        work = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, progress = null, error = null) }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PdfPageRangeError) {
                fail("Choose a page from 1 to ${error.pageCount}, or leave it blank to add at the end.")
            } catch (error: PdfToolPasswordError) {
                fail(if (error.wrong) "That password did not unlock the PDF." else "This PDF is protected. Enter its password.")
            } catch (error: IllegalArgumentException) {
                fail(error.message ?: failure)
            } catch (error: Exception) {
                Log.w("XfilesConvert", failure, error)
                fail(failure)
            } finally {
                mutableState.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    /** The searchable outcome in words: none, some, or all of the pages. */
    private fun searchNotice(outcome: TextLayerOutcome): String = when {
        outcome.pagesWithText == 0 -> "No text was found, so the PDF isn't searchable."
        outcome.pagesWithText < outcome.pages -> "Searchable on ${outcome.pagesWithText} of ${outcome.pages} pages."
        else -> "Searchable on all ${outcome.pages} pages."
    }

    private fun setOutput(output: ConvertOutput) = mutableState.update { it.copy(output = output, error = null) }

    private fun fail(message: String) = mutableState.update { it.copy(error = message) }

    private fun discardOutput() {
        (state.value.output as? ConvertOutput.Pdf)?.pdf?.file?.delete()
    }
}
