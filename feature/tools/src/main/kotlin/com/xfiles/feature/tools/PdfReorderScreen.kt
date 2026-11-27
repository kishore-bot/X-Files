package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzTextButton
import com.xfiles.design.HapticOnChange

private val GridHeight = 520.dp

/**
 * Reorder, turn and remove pages of one PDF, on the shared skeleton. The grid works on the working order only; nothing
 * is written until "Apply order", and the saved copy is a new file.
 */
@Composable
fun PdfReorderScreen(
    onBack: () -> Unit,
    viewModel: PdfReorderViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    HapticOnChange(frame.outputs) { if (it.isNotEmpty()) confirm() }
    HapticOnChange(frame.error) { if (it != null) reject() }
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri.toString()
            viewModel.open(uri)
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.save(uri)
    }
    val back = {
        if (!frame.saving) {
            viewModel.release()
            onBack()
        }
    }
    BackHandler(onBack = back)

    val output = frame.outputs.singleOrNull()
    val step = when {
        frame.saved || output != null -> ToolStep.Save
        !frame.isOpen -> ToolStep.Pick
        else -> ToolStep.Adjust
    }
    ToolScaffold(
        title = "Reorder pages",
        backLabel = "Back to tools",
        onBack = back,
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                if (frame.running) EzTextButton(text = "Cancel", onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth())
                ToolActionBar(
                    label = when {
                        output != null -> "Save PDF · ${output.pages} pages"
                        frame.isOpen -> "Apply order · ${slots.size} ${if (slots.size == 1) "page" else "pages"}"
                        else -> "Choose a PDF"
                    },
                    enabled = if (output != null) !frame.busy else frame.isOpen && !frame.busy && slots.isNotEmpty(),
                    onClick = { if (output != null) save.launch(reorderedName(frame.name)) else viewModel.apply() },
                    icon = EzIcons.Rotate,
                    running = frame.running,
                    progress = frame.progress,
                )
            }
        },
    ) {
        if (frame.saved) {
            item("saved") {
                ToolResult(
                    title = "PDF saved",
                    outputName = reorderedName(frame.name),
                    onDoAnother = viewModel::release,
                )
            }
        } else {
            item("hero") {
                ToolHero(
                    icon = EzIcons.Rotate,
                    tint = EzColors.Accent,
                    title = "Reorder pages",
                    blurb = "Drag pages into order, turn them or remove them. Nothing changes until you apply.",
                    step = step,
                )
            }
            item("source") {
                PdfSourceSection(
                    frame = frame,
                    tint = EzColors.Accent,
                    firstPage = viewModel::firstPreview,
                    onChoose = { choose.launch(arrayOf("application/pdf")) },
                    onUnlock = { password -> pickedUri?.let { viewModel.open(Uri.parse(it), password) } },
                )
            }
            if (frame.isOpen && frame.outputs.isEmpty()) {
                item("hint") {
                    Text(
                        "Long-press a page and drag it into place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                    )
                }
                item("grid") {
                    PdfReorderGrid(
                        slots = slots,
                        enabled = !frame.busy,
                        preview = viewModel::pagePreview,
                        onMove = viewModel::move,
                        onTurn = viewModel::turn,
                        onRemove = viewModel::remove,
                        modifier = Modifier.fillMaxWidth().height(GridHeight),
                    )
                }
            }
            output?.let {
                item("ready") {
                    ReadyCard(title = "Ready to save · ${it.pages} ${if (it.pages == 1) "page" else "pages"}", notes = emptyList())
                }
            }
            frame.error?.let { message -> item("error") { ErrorLine(message) } }
        }
    }
}

private fun reorderedName(name: String): String =
    name.removeSuffix(".pdf").ifBlank { "Document" } + " reordered.pdf"
