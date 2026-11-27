package com.xfiles.feature.tools

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.core.documents.PdfAction
import com.xfiles.core.documents.PdfPaper
import com.xfiles.core.documents.PdfToolOptions
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzTextButton
import com.xfiles.design.HapticOnChange

private val PdfMimeTypes = arrayOf("application/pdf")

/**
 * One page-range or password PDF action (merge, extract, insert, page size, add or remove a password) on the shared
 * skeleton: hero, pick, adjust, a sticky action bar that names the job, and the result.
 */
@Composable
internal fun PdfActionScreen(viewModel: PdfToolsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HapticOnChange(state.output) { if (it != null) confirm() }
    HapticOnChange(state.error) { if (it != null) reject() }
    val action = state.action
    val input = key(action) { rememberTextFieldState() }
    val sourcePassword = remember(action, state.inputs) { TextFieldState() }
    val newPassword = remember(action) { TextFieldState() }
    val confirmPassword = remember(action) { TextFieldState() }
    var paper by rememberSaveable { mutableStateOf(PdfPaper.A4) }
    val chooseSeveral = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), viewModel::pick)
    val chooseOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.pick(listOf(uri))
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.save(uri)
    }
    val back = {
        if (!state.saving) {
            viewModel.close()
            onBack()
        }
    }
    BackHandler(onBack = back)

    val multi = action.inputCount.last > 1
    val tint = EzColors.Accent
    val step = when {
        state.output != null || state.saved -> ToolStep.Save
        state.inputs.isEmpty() -> ToolStep.Pick
        else -> ToolStep.Adjust
    }
    ToolScaffold(
        title = action.label,
        backLabel = "Back to tools",
        onBack = back,
        bottomBar = {
            PdfActionBar(
                viewModel = viewModel,
                state = state,
                input = input.text.toString(),
                sourcePassword = sourcePassword.text.toString(),
                newPassword = newPassword.text.toString(),
                confirmPassword = confirmPassword.text.toString(),
                paper = paper,
                save = { save.launch(action.outputName) },
            )
        },
    ) {
        if (state.saved) {
            item("saved") {
                ToolResult(
                    title = "PDF saved",
                    outputName = action.outputName,
                    onDoAnother = {
                        viewModel.close()
                        viewModel.selectAction(action)
                    },
                )
            }
        } else {
            item("hero") {
                ToolHero(
                    icon = actionIcon(action),
                    tint = tint,
                    title = action.label,
                    blurb = actionBlurb(action),
                    step = step,
                )
            }
            item("pick") {
                PdfPickSection(
                    state = state,
                    multi = multi,
                    tint = tint,
                    onPick = { if (multi) chooseSeveral.launch(PdfMimeTypes) else chooseOne.launch(PdfMimeTypes) },
                    onMove = viewModel::move,
                )
            }
            if (state.inputs.isNotEmpty() && state.output == null) {
                pdfActionAdjust(
                    action = action,
                    enabled = !state.busy,
                    input = input,
                    sourcePassword = sourcePassword,
                    newPassword = newPassword,
                    confirmPassword = confirmPassword,
                    paper = paper,
                    onPaper = { paper = it },
                    sourceLabel = if (state.inputs.size > 1) "First PDF password, if needed" else "Source password, if needed",
                )
            }
            state.error?.let { message -> item("error") { ErrorLine(message) } }
            state.output?.let { output ->
                item("ready") {
                    ReadyCard(
                        title = "Ready to save · ${output.pages} ${if (output.pages == 1) "page" else "pages"}",
                        notes = buildList {
                            if (output.signedSource) add("The new copy does not carry the original digital signature.")
                            if (output.archivalSource) add("The new copy may no longer meet PDF/A archival requirements.")
                            if (output.droppedMetadata) {
                                add("Some links, forms, or other document metadata could not be carried into the new PDF.")
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The sticky bar: the label says the job ("Merge 3 files", "Add password"), or "Save PDF" once the result is ready. */
@Composable
private fun PdfActionBar(
    viewModel: PdfToolsViewModel,
    state: PdfToolsState,
    input: String,
    sourcePassword: String,
    newPassword: String,
    confirmPassword: String,
    paper: PdfPaper,
    save: () -> Unit,
) {
    val action = state.action
    val ready = state.output != null
    val label = when {
        ready -> "Save PDF"
        state.inputs.isEmpty() -> if (action.inputCount.last > 1) "Choose PDFs" else "Choose a PDF"
        else -> actionLabel(action, state.inputs.size, paper)
    }
    val valid = state.inputs.size in action.inputCount && when (action) {
        PdfAction.Extract, PdfAction.Delete, PdfAction.Rotate, PdfAction.Insert -> input.isNotBlank()
        PdfAction.Protect -> newPassword.isNotEmpty() && newPassword == confirmPassword
        else -> true
    }
    Column(Modifier.fillMaxWidth()) {
        if (state.running) EzTextButton(text = "Cancel", onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth())
        ToolActionBar(
            label = label,
            enabled = if (ready) !state.busy else !state.busy && state.inputs.isNotEmpty() && valid,
            onClick = {
                if (ready) {
                    save()
                } else {
                    viewModel.prepare(
                        PdfToolOptions(
                            input,
                            90,
                            paper,
                            sourcePassword.takeIf { it.isNotEmpty() },
                            newPassword,
                        ),
                    )
                }
            },
            icon = actionIcon(action),
            running = state.running,
            progress = state.progress,
        )
    }
}

/** The source list: a drop-zone while empty, then the chosen PDFs in order, with move controls for multi-file jobs. */
@Composable
private fun PdfPickSection(
    state: PdfToolsState,
    multi: Boolean,
    tint: Color,
    onPick: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val locked = state.busy || state.output != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.inputs.isEmpty()) {
            FilePickCard(
                title = if (multi) "Choose PDFs" else "Choose a PDF",
                hint = if (multi) "Pick two to eight PDFs. The first one leads." else "Pick one PDF from this phone",
                icon = EzIcons.PdfFile,
                tint = tint,
                onClick = onPick,
            )
        } else {
            state.inputs.forEachIndexed { index, file ->
                val up: (() -> Unit)? = if (multi && !locked && index > 0) ({ onMove(index, index - 1) }) else null
                val down: (() -> Unit)? = if (multi && !locked && index < state.inputs.lastIndex) ({ onMove(index, index + 1) }) else null
                PickedFileCard(
                    name = file.name,
                    meta = if (multi) "File ${index + 1} of ${state.inputs.size}" else "PDF",
                    thumbnail = null,
                    onRemove = null,
                    onMoveUp = up,
                    onMoveDown = down,
                )
            }
            if (!locked) EzTextButton(text = "Choose again", onClick = onPick)
        }
    }
}

private fun actionLabel(action: PdfAction, count: Int, paper: PdfPaper): String = when (action) {
    PdfAction.Merge -> "Merge $count ${if (count == 1) "file" else "files"}"
    PdfAction.Extract -> "Extract pages"
    PdfAction.Insert -> "Insert pages"
    PdfAction.Resize -> "Resize to ${paper.label}"
    PdfAction.Protect -> "Add password"
    PdfAction.Unlock -> "Remove password"
    PdfAction.Rotate, PdfAction.Delete -> action.label
}

private fun actionIcon(action: PdfAction): ImageVector = when (action) {
    PdfAction.Merge -> EzIcons.MergePages
    PdfAction.Extract -> EzIcons.ExtractPages
    PdfAction.Insert -> EzIcons.InsertPages
    PdfAction.Resize -> EzIcons.AspectRatio
    PdfAction.Protect -> EzIcons.Lock
    PdfAction.Unlock -> EzIcons.Unlock
    PdfAction.Rotate -> EzIcons.Rotate
    PdfAction.Delete -> EzIcons.Delete
}

private fun actionBlurb(action: PdfAction): String = when (action) {
    PdfAction.Merge -> "Join PDFs into one, in the order you set. Nothing leaves this phone."
    PdfAction.Extract -> "Keep only the pages you name, in the order you type them."
    PdfAction.Insert -> "Place the pages of a second PDF before a page of the first."
    PdfAction.Resize -> "Fit every page to a paper size. Content scales to the new page."
    PdfAction.Protect -> "Lock the PDF with a password. You will need it to open the file."
    PdfAction.Unlock -> "Save a copy without its password. Enter the password it has now."
    PdfAction.Rotate -> "Turn pages a quarter turn."
    PdfAction.Delete -> "Remove the pages you name."
}
