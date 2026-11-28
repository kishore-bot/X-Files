package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.EzTextButton
import com.xfiles.design.HapticOnChange
import com.xfiles.design.glassCard
import com.xfiles.documents.MAX_SAVED_SIGNATURES


/**
 * A drawn signature, placed on a page. The pad and the saved signatures are visual only: they're drawn on the page as
 * vector strokes, not signed with a key.
 */
@Composable
fun PdfSignScreen(
    onBack: () -> Unit,
    viewModel: PdfSignViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    HapticOnChange(frame.outputs) { if (it.isNotEmpty()) confirm() }
    HapticOnChange(frame.error) { if (it != null) reject() }
    val input by viewModel.input.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val protection by viewModel.protection.collectAsStateWithLifecycle()
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri.toString()
            viewModel.open(uri)
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) viewModel.save(uri)
    }
    val back = {
        if (!frame.saving) {
            viewModel.release()
            onBack()
        }
    }
    BackHandler(onBack = back)

    val ready = frame.outputs.isNotEmpty()
    val step = when {
        frame.saved || ready -> ToolStep.Save
        !frame.isOpen -> ToolStep.Pick
        input.strokes.isEmpty() -> ToolStep.Adjust
        else -> ToolStep.Adjust
    }
    ToolScaffold(
        title = "Sign",
        backLabel = "Back to tools",
        onBack = back,
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                if (frame.running) EzTextButton(text = "Cancel", onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth())
                ToolActionBar(
                    label = if (ready) "Save PDF" else "Sign page ${input.page + 1}",
                    enabled = !frame.busy && (ready || (frame.isOpen && input.strokes.isNotEmpty())),
                    onClick = { if (ready) save.launch(signedName(frame.name)) else viewModel.apply() },
                    icon = EzIcons.Edit,
                    running = frame.running,
                    progress = frame.progress,
                )
            }
        },
    ) {
        if (frame.saved) {
            item("saved") {
                ToolResult(
                    title = "Signed PDF saved",
                    outputName = signedName(frame.name),
                    onDoAnother = viewModel::release,
                )
            }
        } else {
            item("hero") {
                ToolHero(
                    icon = EzIcons.Signature,
                    tint = EzColors.Accent,
                    title = "Signature",
                    blurb = "Draw your signature, then place it on a page. It is drawn in, not verified.",
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
            if (frame.isOpen && !ready) {
                item("pad-title") {
                    Text("1 · Draw your signature", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                }
                item("pad") {
                    SignaturePad(
                        strokes = input.strokes,
                        onStroke = viewModel::addStroke,
                        modifier = Modifier.fillMaxWidth().aspectRatio(PdfSignViewModel.ASPECT),
                    )
                }
                item("pad-actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        EzTextButton(text = "Undo", onClick = viewModel::undoStroke, enabled = input.strokes.isNotEmpty())
                        EzTextButton(text = "Clear", onClick = viewModel::clearPad, enabled = input.strokes.isNotEmpty())
                        EzTextButton(
                            text = "Keep for later",
                            onClick = viewModel::saveCurrent,
                            enabled = input.strokes.isNotEmpty() && saved.size < MAX_SAVED_SIGNATURES,
                        )
                    }
                }
                if (saved.isNotEmpty()) {
                    item("saved-list") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Kept signatures (${saved.size} of $MAX_SAVED_SIGNATURES)",
                                style = MaterialTheme.typography.labelLarge,
                                color = EzColors.TextSecondary,
                            )
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                saved.forEachIndexed { index, signature ->
                                    Column(
                                        Modifier.glassCard(EzShapes.Card, 0.45f).padding(10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        SignatureThumb(signature.strokes)
                                        Row {
                                            EzTextButton(text = "Use", onClick = { viewModel.useSaved(index) })
                                            EzTextButton(text = "Remove", onClick = { viewModel.deleteSaved(index) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item("place-title") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "2 · Place it on the page",
                            style = MaterialTheme.typography.titleSmall,
                            color = EzColors.Text,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.setPage(input.page - 1) }, enabled = input.page > 0 && !frame.busy) {
                            androidx.compose.material3.Icon(EzIcons.ChevronLeft, contentDescription = "Previous page")
                        }
                        Text(
                            "Page ${input.page + 1} of ${frame.pageCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EzColors.TextSecondary,
                        )
                        IconButton(
                            onClick = { viewModel.setPage(input.page + 1) },
                            enabled = input.page < frame.pageCount - 1 && !frame.busy,
                        ) {
                            androidx.compose.material3.Icon(EzIcons.ChevronRight, contentDescription = "Next page")
                        }
                    }
                }
                item("placement") { SignPlacement(viewModel = viewModel, input = input) }
                item("hint") {
                    Text(
                        "Drag the signature to move it. Drag its corner to resize.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                    )
                }
                item("protection") {
                    PdfProtectionCards(selected = protection, enabled = !frame.busy, onSelect = viewModel::setProtection)
                }
            }
            if (ready) {
                item("ready") {
                    ReadyCard(title = "Signed page ${input.page + 1}", notes = listOf("Choose Save PDF to keep the signed copy."))
                }
            }
            frame.error?.let { message -> item("error") { ErrorLine(message) } }
        }
    }
}

private fun signedName(name: String): String =
    name.removeSuffix(".pdf").ifBlank { "Document" } + " signed.pdf"
