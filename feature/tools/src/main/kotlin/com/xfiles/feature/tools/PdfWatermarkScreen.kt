package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzChip
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzTextButton
import com.xfiles.design.HapticOnChange
import com.xfiles.design.glassCard
import com.xfiles.documents.PdfSpanParse
import com.xfiles.documents.PdfWatermarkSpec
import com.xfiles.documents.parsePdfSpans

private val WatermarkTint = Color(0xFF7A5AF8)
private val WatermarkColours = listOf(0x808080 to "Grey", 0xC62828 to "Red", 0x1565C0 to "Blue")

/**
 * Text over the chosen pages. A live preview draws the text over the first page as the engine will place it, and the
 * sliders, the 3 by 3 position picker and the colour set the spec.
 */
@Composable
fun PdfWatermarkScreen(
    onBack: () -> Unit,
    viewModel: PdfWatermarkViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    HapticOnChange(frame.outputs) { if (it.isNotEmpty()) confirm() }
    HapticOnChange(frame.error) { if (it != null) reject() }
    val spec by viewModel.spec.collectAsStateWithLifecycle()
    val protection by viewModel.protection.collectAsStateWithLifecycle()
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pagesText by rememberSaveable { mutableStateOf("") }
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

    val pagesProblem = remember(pagesText, frame.pageCount) {
        if (pagesText.isBlank()) null else (parsePdfSpans(pagesText, frame.pageCount) as? PdfSpanParse.Invalid)?.message
    }
    val specProblem = spec.validationError()
    val ready = frame.outputs.isNotEmpty()
    val step = when {
        frame.saved || ready -> ToolStep.Save
        !frame.isOpen -> ToolStep.Pick
        else -> ToolStep.Adjust
    }
    ToolScaffold(
        title = "Watermark",
        backLabel = "Back to tools",
        onBack = back,
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                if (frame.running) EzTextButton(text = "Cancel", onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth())
                ToolActionBar(
                    label = if (ready) "Save PDF" else "Apply watermark",
                    enabled = !frame.busy && (ready || (frame.isOpen && pagesProblem == null && specProblem == null)),
                    onClick = { if (ready) save.launch(watermarkedName(frame.name)) else viewModel.apply() },
                    icon = EzIcons.TextT,
                    running = frame.running,
                    progress = frame.progress,
                )
            }
        },
    ) {
        if (frame.saved) {
            item("saved") {
                ToolResult(
                    title = "Watermarked PDF saved",
                    outputName = watermarkedName(frame.name),
                    onDoAnother = viewModel::release,
                )
            }
        } else {
            item("hero") {
                ToolHero(
                    icon = EzIcons.TextT,
                    tint = WatermarkTint,
                    title = "Watermark",
                    blurb = "Stamp text over pages. The preview shows where it lands.",
                    step = step,
                )
            }
            item("source") {
                PdfSourceSection(
                    frame = frame,
                    tint = WatermarkTint,
                    firstPage = viewModel::firstPreview,
                    onChoose = { choose.launch(arrayOf("application/pdf")) },
                    onUnlock = { password -> pickedUri?.let { viewModel.open(Uri.parse(it), password) } },
                )
            }
            if (frame.isOpen && !ready) {
                item("preview") {
                    WatermarkPreview(spec = spec, pagePreview = viewModel::pagePreview)
                }
                item("text") {
                    OutlinedTextField(
                        value = spec.text,
                        onValueChange = { text -> viewModel.edit { it.copy(text = text.take(PdfWatermarkSpec.MAX_WATERMARK_CHARS)) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !frame.busy,
                        singleLine = true,
                        label = { Text("Watermark text") },
                        supportingText = { Text(specProblem ?: "Up to ${PdfWatermarkSpec.MAX_WATERMARK_CHARS} characters.") },
                        isError = specProblem != null,
                    )
                }
                item("sliders") {
                    Column(
                        Modifier.fillMaxWidth().glassCard(com.xfiles.design.EzShapes.Card, 0.45f).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SliderRow("Size ${spec.fontSize.toInt()} pt", spec.fontSize, 12f..160f, !frame.busy) { v ->
                            viewModel.edit { it.copy(fontSize = v) }
                        }
                        SliderRow("Opacity ${(spec.opacity * 100).toInt()}%", spec.opacity, 0.05f..1f, !frame.busy) { v ->
                            viewModel.edit { it.copy(opacity = v) }
                        }
                        SliderRow("Rotation ${spec.angleDegrees.toInt()}°", spec.angleDegrees, -90f..90f, !frame.busy) { v ->
                            viewModel.edit { it.copy(angleDegrees = v) }
                        }
                    }
                }
                item("position") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Position", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                            EzChip(
                                label = "Tiled",
                                selected = spec.position == com.xfiles.documents.PdfWatermarkPosition.Tiled,
                                onClick = { viewModel.edit { it.copy(position = com.xfiles.documents.PdfWatermarkPosition.Tiled) } },
                            )
                        }
                        WatermarkPositionPicker(
                            selected = spec.position,
                            enabled = !frame.busy,
                            onSelect = { position -> viewModel.edit { it.copy(position = position) } },
                        )
                    }
                }
                item("colour") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Colour", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WatermarkColours.forEach { (colour, label) ->
                                EzChip(
                                    label = label,
                                    selected = spec.color == colour,
                                    onClick = { viewModel.edit { it.copy(color = colour) } },
                                )
                            }
                        }
                    }
                }
                item("pages") {
                    OutlinedTextField(
                        value = pagesText,
                        onValueChange = { text ->
                            pagesText = text
                            val pages = if (text.isBlank()) null else {
                                (parsePdfSpans(text, frame.pageCount) as? PdfSpanParse.Valid)?.spans?.flatMap { it.start..it.end }
                            }
                            viewModel.edit { it.copy(pages = pages) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !frame.busy,
                        singleLine = true,
                        label = { Text("Pages") },
                        placeholder = { Text("All pages, or 1-3, 6") },
                        supportingText = { Text(pagesProblem ?: "Leave blank for every page.") },
                        isError = pagesProblem != null,
                    )
                }
                item("protection") {
                    PdfProtectionCards(selected = protection, enabled = !frame.busy, onSelect = viewModel::setProtection)
                }
            }
            if (ready) {
                item("ready") {
                    ReadyCard(title = "Watermark applied to ${frame.outputs.firstOrNull()?.pages ?: 0} pages", notes = emptyList())
                }
            }
            frame.error?.let { message -> item("error") { ErrorLine(message) } }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = EzColors.Text)
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

private fun watermarkedName(name: String): String =
    name.removeSuffix(".pdf").ifBlank { "Document" } + " watermarked.pdf"
