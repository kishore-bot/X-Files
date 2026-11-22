package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzTextButton
import com.xfiles.design.HapticOnChange
import com.xfiles.documents.PdfSpanParse
import com.xfiles.documents.PdfSplitPlan
import com.xfiles.documents.planPdfSplit

private val SplitTint = Color(0xFF0B7F86)
private val GridHeight = 460.dp

/**
 * Splits one PDF three ways: every page, every N pages, or custom cuts tapped on the page grid (or typed as ranges).
 * Each part can be renamed, and the parts are written into a folder the user picks.
 */
@Composable
fun PdfSplitScreen(
    onBack: () -> Unit,
    viewModel: PdfSplitViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val input by viewModel.tool.collectAsStateWithLifecycle()
    HapticOnChange(frame.outputs) { if (it.isNotEmpty()) confirm() }
    HapticOnChange(frame.error) { if (it != null) reject() }
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri.toString()
            viewModel.open(uri)
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.saveParts(uri)
    }
    val back = {
        if (!frame.saving) {
            viewModel.release()
            onBack()
        }
    }
    BackHandler(onBack = back)

    val plan = input.plan
    val parse = remember(plan, frame.pageCount) { planPdfSplit(plan, frame.pageCount) }
    val spans = (parse as? PdfSpanParse.Valid)?.spans.orEmpty()
    // The cuts the parts show: the custom cuts when set, otherwise the ends of the parts the plan makes.
    val shownCuts = spans.dropLast(1).map { it.end }.toSet()
    val names = remember(spans, input.names, frame.name) { viewModel.partNames(spans) }
    val parts = frame.outputs.size
    val step = when {
        frame.saved || frame.outputs.isNotEmpty() -> ToolStep.Save
        !frame.isOpen -> ToolStep.Pick
        else -> ToolStep.Adjust
    }

    ToolScaffold(
        title = "Split PDF",
        backLabel = "Back to tools",
        onBack = back,
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                if (frame.running) EzTextButton(text = "Cancel", onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth())
                ToolActionBar(
                    label = when {
                        parts > 0 -> "Save $parts ${if (parts == 1) "PDF" else "PDFs"} to a folder"
                        parse is PdfSpanParse.Valid -> "Split into ${spans.size} ${if (spans.size == 1) "PDF" else "PDFs"}"
                        else -> "Split"
                    },
                    enabled = !frame.busy && (parts > 0 || (frame.isOpen && parse is PdfSpanParse.Valid)),
                    onClick = { if (parts > 0) folder.launch(null) else viewModel.split(spans) },
                    icon = EzIcons.Lines,
                    running = frame.running,
                    progress = frame.progress,
                )
            }
        },
    ) {
        if (frame.saved) {
            item("saved") {
                ToolResult(
                    title = "Parts saved",
                    outputName = "$parts ${if (parts == 1) "file" else "files"} in the folder you chose",
                    onDoAnother = viewModel::release,
                )
            }
        } else {
            item("hero") {
                ToolHero(
                    icon = EzIcons.Lines,
                    tint = SplitTint,
                    title = "Split PDF",
                    blurb = "Cut one PDF into several. Tap pages to choose where each part ends.",
                    step = step,
                )
            }
            item("source") {
                PdfSourceSection(
                    frame = frame,
                    tint = SplitTint,
                    firstPage = viewModel::firstPreview,
                    onChoose = { choose.launch(arrayOf("application/pdf")) },
                    onUnlock = { password -> pickedUri?.let { viewModel.open(Uri.parse(it), password) } },
                )
            }
            if (frame.isOpen && frame.outputs.isEmpty()) {
                item("modes") {
                    SplitModeCards(
                        plan = plan,
                        enabled = !frame.busy,
                        onPlan = viewModel::setPlan,
                        onCustom = { viewModel.startCustom(shownCuts) },
                    )
                }
                when (plan) {
                    is PdfSplitPlan.EveryPages -> item("every") {
                        EveryNStepper(
                            pagesText = plan.pagesText,
                            pageCount = frame.pageCount,
                            enabled = !frame.busy,
                            onChange = { viewModel.setPlan(PdfSplitPlan.EveryPages(it)) },
                        )
                    }
                    is PdfSplitPlan.Ranges -> item("ranges") {
                        OutlinedTextField(
                            value = plan.text,
                            onValueChange = { viewModel.setPlan(PdfSplitPlan.Ranges(it)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !frame.busy,
                            singleLine = true,
                            label = { Text("Page ranges") },
                            placeholder = { Text("1-3, 4-8, 9-") },
                            supportingText = { Text(rangeMessage(parse)) },
                            isError = parse is PdfSpanParse.Invalid,
                        )
                    }
                    PdfSplitPlan.OnePerPage -> Unit
                }
                item("grid-hint") {
                    Text(
                        "Tap a page to end a PDF after it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                    )
                }
                item("grid") {
                    SplitPageGrid(
                        pageCount = frame.pageCount,
                        cuts = shownCuts,
                        enabled = !frame.busy,
                        preview = viewModel::pagePreview,
                        onTap = { page ->
                            if (plan !is PdfSplitPlan.Ranges) viewModel.startCustom(shownCuts)
                            viewModel.toggleCut(page)
                        },
                        modifier = Modifier.fillMaxWidth().height(GridHeight),
                    )
                }
                if (spans.isNotEmpty()) {
                    item("parts-hint") {
                        Text(rangeMessage(parse), style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
                    }
                    item("names") {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SplitPartNames(
                                spans = spans,
                                names = names,
                                enabled = !frame.busy,
                                onName = viewModel::setName,
                            )
                        }
                    }
                }
            }
            if (frame.outputs.isNotEmpty()) {
                item("ready") {
                    ReadyCard(
                        title = "$parts ${if (parts == 1) "file" else "files"} ready · ${frame.outputs.sumOf { it.pages }} pages",
                        notes = listOf("Choose a folder to write them into."),
                    )
                }
            }
            frame.error?.let { message -> item("error") { ErrorLine(message) } }
        }
    }
}

private fun rangeMessage(parse: PdfSpanParse): String = when (parse) {
    is PdfSpanParse.Valid -> "${parse.spans.size} ${if (parse.spans.size == 1) "part" else "parts"} · no page is shared"
    is PdfSpanParse.Invalid -> parse.message
}
