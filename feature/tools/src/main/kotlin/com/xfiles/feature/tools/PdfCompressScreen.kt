package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.HapticOnChange
import com.xfiles.documents.PdfCompressQuality
import com.xfiles.design.EzTextButton


/**
 * Compression at a chosen quality, with the source's protection kept or replaced on the result. Each quality is a card
 * with bars for how much detail it keeps.
 */
@Composable
fun PdfCompressScreen(
    onBack: () -> Unit,
    viewModel: PdfCompressViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    HapticOnChange(frame.outputs) { if (it.isNotEmpty()) confirm() }
    HapticOnChange(frame.error) { if (it != null) reject() }
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
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
        else -> ToolStep.Adjust
    }
    ToolScaffold(
        title = "Compress",
        backLabel = "Back to tools",
        onBack = back,
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                if (frame.running) EzTextButton(text = "Cancel", onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth())
                ToolActionBar(
                    label = if (ready) "Save PDF" else "Compress · ${quality.label}",
                    enabled = !frame.busy && (ready || frame.isOpen),
                    onClick = { if (ready) save.launch(compressedName(frame.name)) else viewModel.compress() },
                    icon = EzIcons.Archive,
                    running = frame.running,
                    progress = frame.progress,
                )
            }
        },
    ) {
        if (frame.saved) {
            item("saved") {
                ToolResult(
                    title = "Compressed PDF saved",
                    outputName = compressedName(frame.name),
                    sizeBefore = summary?.beforeBytes,
                    sizeAfter = summary?.afterBytes,
                    onDoAnother = viewModel::release,
                )
            }
        } else {
            item("hero") {
                ToolHero(
                    icon = EzIcons.CompressPages,
                    tint = EzColors.Accent,
                    title = "Compress",
                    blurb = "Make a PDF smaller on this phone. Pick how much image detail to keep.",
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
                item("quality") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Quality", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                        PdfCompressQuality.entries.forEach { level ->
                            ChoiceCard(
                                title = level.label,
                                detail = level.detail,
                                selected = level == quality,
                                enabled = !frame.busy,
                                onClick = { viewModel.setQuality(level) },
                                modifier = Modifier.fillMaxWidth(),
                                leading = { DetailBars(filled = PdfCompressQuality.entries.size - level.ordinal, selected = level == quality) },
                            )
                        }
                    }
                }
                item("protection") {
                    PdfProtectionCards(selected = protection, enabled = !frame.busy, onSelect = viewModel::setProtection)
                }
            }
            summary?.takeIf { ready }?.let { result ->
                item("summary") {
                    val saved = result.beforeBytes - result.afterBytes
                    val percent = if (result.beforeBytes > 0) saved * 100 / result.beforeBytes else 0L
                    ReadyCard(
                        title = "${formatBytes(result.beforeBytes)} → ${formatBytes(result.afterBytes)}",
                        notes = buildList {
                            if (saved > 0 && result.beforeBytes > 0) {
                                add("$percent% smaller · ${result.pages} ${if (result.pages == 1) "page" else "pages"} kept")
                            } else {
                                add("This file was already compact. The new copy is no smaller, so keeping the original is the better choice.")
                            }
                            if (result.imagesRecompressed > 0) {
                                add("${result.imagesRecompressed} ${if (result.imagesRecompressed == 1) "image was" else "images were"} re-encoded at a smaller size.")
                            }
                            if (result.duplicatesMerged > 0) {
                                add("${result.duplicatesMerged} repeated ${if (result.duplicatesMerged == 1) "stream was" else "streams were"} stored once.")
                            }
                        },
                    )
                }
            }
            frame.error?.let { message -> item("error") { ErrorLine(message) } }
        }
    }
}

/** Filled bars for how much detail a quality keeps: more bars, more detail. */
@Composable
private fun DetailBars(filled: Int, selected: Boolean) {
    val track = if (selected) Color.White.copy(alpha = 0.35f) else EzColors.FieldOutline
    val ink = if (selected) EzColors.OnPrimary else EzColors.Primary
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { bar ->
            Box(
                Modifier
                    .width(10.dp)
                    .height((12 + bar * 6).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (bar < filled) ink else track),
            )
        }
    }
}

private fun compressedName(name: String): String =
    name.removeSuffix(".pdf").ifBlank { "Document" } + " compressed.pdf"
