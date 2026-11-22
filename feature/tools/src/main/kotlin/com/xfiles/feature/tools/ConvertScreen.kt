package com.xfiles.feature.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzTextButton
import com.xfiles.design.glassCard

/** The pickers and the camera a convert tool can open. The route owns the system launchers; the content is stateless. */
internal class ConvertLaunch(
    val pickPictures: () -> Unit,
    val pickPicture: () -> Unit,
    val pickPdf: () -> Unit,
    val scan: () -> Unit,
)

/**
 * One create or convert tool on the shared skeleton: hero, pick, adjust, a sticky action bar that names the job, and the
 * result. The tool's own sections live in ConvertPictures, ConvertText and ConvertPdfOutputs.
 */
@Composable
internal fun ConvertToolScreen(viewModel: ConvertViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val text = rememberTextFieldState()
    val position = rememberTextFieldState()
    val password = remember(state.pdf) { TextFieldState() }
    var scanning by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.openScanner) {
        if (state.openScanner) {
            viewModel.scannerOpened()
            scanning = true
        }
    }
    if (scanning) {
        ScanCamera(viewModel.scanProcessing, state.pictures.size, onPage = viewModel::addScan, onDone = { scanning = false })
        return
    }
    LaunchedEffect(state.draft) {
        state.draft?.let {
            text.setTextAndPlaceCursorAtEnd(it)
            viewModel.draftConsumed()
        }
    }
    // The live preview follows the typed words, so the view model is told about every edit.
    LaunchedEffect(Unit) {
        snapshotFlow { text.text.toString() }.collect { viewModel.setTypedText(it) }
    }

    val pictures = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { viewModel.addPictures(it) }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { viewModel.addPictures(listOf(it)) } }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::pickPdf) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) viewModel.run(password = password.text.toString().ifEmpty { null }, target = tree)
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.run(target = uri)
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let(viewModel::savePdf) }
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(viewModel::saveText) }

    val launch = ConvertLaunch(
        pickPictures = { pictures.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        pickPicture = { picture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        pickPdf = { pdf.launch(arrayOf("application/pdf")) },
        scan = { scanning = true },
    )
    val tool = state.tool
    val output = state.output
    val step = when {
        output is ConvertOutput.Pdf || output is ConvertOutput.Text -> ToolStep.Save
        !isReady(state, text.text.toString()) -> ToolStep.Pick
        else -> ToolStep.Adjust
    }

    ToolScaffold(
        title = tool.label,
        backLabel = "Back to tools",
        onBack = onBack,
        bottomBar = {
            if (output !is ConvertOutput.Done) {
                ConvertBottomBar(
                    state = state,
                    typed = text.text.toString(),
                    position = position.text.toString(),
                    password = password.text.toString(),
                    onRun = {
                        when (tool) {
                            ConvertTool.PdfToPictures -> folder.launch(null)
                            ConvertTool.PdfToZip -> zip.launch(state.pdf?.name?.removeSuffix(".pdf").orEmpty() + ".zip")
                            ConvertTool.AddPictures -> viewModel.run(
                                text = position.text.toString(),
                                password = password.text.toString().ifEmpty { null },
                            )
                            else -> viewModel.run(text = text.text.toString())
                        }
                    },
                    onSave = { result ->
                        when (result) {
                            is ConvertOutput.Pdf -> savePdf.launch(result.suggestedName)
                            is ConvertOutput.Text -> saveText.launch(result.suggestedName)
                            else -> Unit
                        }
                    },
                    onCancel = viewModel::cancel,
                )
            }
        },
    ) {
        when (output) {
            is ConvertOutput.Done -> item("done") {
                ToolResult(
                    title = output.message,
                    outputName = tool.label,
                    onDoAnother = {
                        viewModel.close()
                        viewModel.select(tool)
                    },
                )
            }
            else -> {
                item("hero") {
                    ToolHero(
                        icon = toolIcon(tool),
                        tint = toolTint(tool),
                        title = tool.label,
                        blurb = toolBlurb(tool),
                        step = step,
                    )
                }
                toolSections(
                    state = state,
                    viewModel = viewModel,
                    launch = launch,
                    text = text,
                    position = position,
                    password = password,
                )
                state.error?.let { message -> item("error") { ErrorLine(message) } }
                output?.let { result -> item("result") { ConvertResultCard(result) } }
            }
        }
    }
}

/** The sections for the tool on screen, in its own order. */
private fun LazyListScope.toolSections(
    state: ConvertState,
    viewModel: ConvertViewModel,
    launch: ConvertLaunch,
    text: TextFieldState,
    position: TextFieldState,
    password: TextFieldState,
) {
    when (state.tool) {
        ConvertTool.PicturesToPdf, ConvertTool.ScanToPdf, ConvertTool.PictureToText -> {
            pictureSections(state, viewModel, launch)
        }
        ConvertTool.AddPictures -> {
            pdfSourceSection(state, viewModel, launch, password)
            pictureSections(state, viewModel, launch)
            item("position") { PositionField(position, enabled = !state.busy) }
        }
        ConvertTool.TextToPdf -> textSections(state, viewModel, text)
        ConvertTool.PdfToPictures, ConvertTool.PdfToText, ConvertTool.PdfToZip -> {
            pdfSourceSection(state, viewModel, launch, password)
            pdfOutputSections(state, viewModel)
        }
    }
}

/** The bottom bar: the job's name, or the save of a finished result. Disabled until the tool has what it needs. */
@Composable
private fun ConvertBottomBar(
    state: ConvertState,
    typed: String,
    position: String,
    password: String,
    onRun: () -> Unit,
    onSave: (ConvertOutput) -> Unit,
    onCancel: () -> Unit,
) {
    val output = state.output
    val pages = state.pictures.size
    val label = when {
        output is ConvertOutput.Pdf -> "Save PDF · ${output.pdf.pages} ${if (output.pdf.pages == 1) "page" else "pages"}"
        output is ConvertOutput.Text -> "Save text"
        else -> when (state.tool) {
            ConvertTool.PicturesToPdf, ConvertTool.ScanToPdf -> if (pages == 0) "Add pictures" else "Make PDF · $pages ${if (pages == 1) "page" else "pages"}"
            ConvertTool.TextToPdf -> "Make PDF"
            ConvertTool.PictureToText -> "Read text"
            ConvertTool.AddPictures -> "Add $pages ${if (pages == 1) "picture" else "pictures"} to PDF"
            ConvertTool.PdfToPictures -> "Export pictures"
            ConvertTool.PdfToText -> "Extract text"
            ConvertTool.PdfToZip -> "Create ZIP"
        }
    }
    val ready = isReady(state, typed)
    Column(Modifier.fillMaxWidth()) {
        if (state.busy) EzTextButton(text = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
        ToolActionBar(
            label = label,
            enabled = !state.busy && (output != null || ready),
            onClick = { if (output != null) onSave(output) else onRun() },
            icon = toolIcon(state.tool),
            running = state.busy,
            progress = convertProgress(state.progress),
        )
    }
}

/** Whether the tool has its input: pictures, a PDF, or typed text. */
internal fun isReady(state: ConvertState, typed: String): Boolean = when (state.tool.input) {
    ToolInput.Pictures, ToolInput.Camera, ToolInput.Picture -> state.pictures.isNotEmpty()
    ToolInput.Pdf -> state.pdf != null
    ToolInput.PdfAndPictures -> state.pdf != null && state.pictures.isNotEmpty()
    ToolInput.Typing -> typed.isNotBlank()
}

/** The result of a finished PDF or text, shown before it is saved. */
@Composable
private fun ConvertResultCard(output: ConvertOutput) {
    when (output) {
        is ConvertOutput.Pdf -> ReadyCard(
            title = "Ready to save · ${output.pdf.pages} ${if (output.pdf.pages == 1) "page" else "pages"}",
            notes = listOfNotNull(output.notice),
        )
        is ConvertOutput.Text -> SelectionContainer {
            Text(
                output.text.take(4000),
                Modifier.fillMaxWidth().glassCard().padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = EzColors.Text,
            )
        }
        is ConvertOutput.Done -> Unit
    }
}

private fun toolIcon(tool: ConvertTool): ImageVector = when (tool) {
    ConvertTool.PicturesToPdf, ConvertTool.AddPictures, ConvertTool.PdfToPictures -> EzIcons.Image
    ConvertTool.ScanToPdf -> EzIcons.Camera
    ConvertTool.TextToPdf, ConvertTool.PdfToText -> EzIcons.TextT
    ConvertTool.PictureToText -> EzIcons.Search
    ConvertTool.PdfToZip -> EzIcons.Archive
}

private fun toolTint(tool: ConvertTool): Color = when (tool) {
    ConvertTool.PicturesToPdf -> Color(0xFF7A4A2E)
    ConvertTool.ScanToPdf -> Color(0xFF373C47)
    ConvertTool.TextToPdf -> Color(0xFF6832C1)
    ConvertTool.PictureToText -> Color(0xFF2F3BB5)
    ConvertTool.AddPictures -> Color(0xFF8E5BD9)
    ConvertTool.PdfToPictures -> Color(0xFF2E9E6B)
    ConvertTool.PdfToText -> Color(0xFF2F3BB5)
    ConvertTool.PdfToZip -> Color(0xFFB0124F)
}

private fun toolBlurb(tool: ConvertTool): String = when (tool) {
    ConvertTool.PicturesToPdf -> "Put pictures in order and make one PDF. Nothing leaves this phone."
    ConvertTool.ScanToPdf -> "Scan paper pages with the camera, straighten them and make a PDF."
    ConvertTool.TextToPdf -> "Type or paste text, pick a look, and see each page before you make the PDF."
    ConvertTool.PictureToText -> "Read the words in a picture on this phone."
    ConvertTool.AddPictures -> "Add pictures as pages to a PDF, before a page you name."
    ConvertTool.PdfToPictures -> "Save every page of a PDF as a picture, in the folder you choose."
    ConvertTool.PdfToText -> "Pull the text out of a PDF and save it as a text file."
    ConvertTool.PdfToZip -> "Put a PDF's pages into a ZIP archive."
}

/** Progress as a fraction, or null while the job cannot tell yet. */
internal fun convertProgress(progress: Float?): Float? = progress?.coerceIn(0f, 1f)
