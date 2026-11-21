package com.xfiles.feature.tools

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.documents.PdfSpanParse
import com.xfiles.documents.PdfWatermarkPosition
import com.xfiles.documents.PdfWatermarkSpec
import com.xfiles.documents.parsePdfSpans
import kotlin.math.ceil
import kotlin.math.min

private val WatermarkColours = listOf(0x808080 to "Grey", 0xC62828 to "Red", 0x1565C0 to "Blue")

/**
 * Text over the chosen pages. The preview draws the same placement the engine
 * uses, over the first page, so what's seen is close to the saved result.
 */
@Composable
fun PdfWatermarkScreen(
    onBack: () -> Unit,
    viewModel: PdfWatermarkViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DedicatedToolHeader(title = "Watermark", onBack = back)
        PdfSourceCard(
            frame = frame,
            onChoose = { choose.launch(arrayOf("application/pdf")) },
            onUnlock = { password -> pickedUri?.let { viewModel.open(Uri.parse(it), password) } },
        )
        if (frame.isOpen && frame.outputs.isEmpty()) {
            WatermarkPreview(spec = spec, pagePreview = viewModel::pagePreview)
            OutlinedTextField(
                value = spec.text,
                onValueChange = { text -> viewModel.edit { it.copy(text = text.take(PdfWatermarkSpec.MAX_WATERMARK_CHARS)) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !frame.busy,
                singleLine = true,
                label = { Text("Text") },
            )
            LabelledSlider("Size ${spec.fontSize.toInt()} pt", spec.fontSize, 12f..160f) { v ->
                viewModel.edit { it.copy(fontSize = v) }
            }
            LabelledSlider("Opacity ${(spec.opacity * 100).toInt()}%", spec.opacity, 0.05f..1f) { v ->
                viewModel.edit { it.copy(opacity = v) }
            }
            LabelledSlider("Angle ${spec.angleDegrees.toInt()}°", spec.angleDegrees, -90f..90f) { v ->
                viewModel.edit { it.copy(angleDegrees = v) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PdfWatermarkPosition.entries.forEach { position ->
                    FilterChip(
                        selected = spec.position == position,
                        onClick = { viewModel.edit { it.copy(position = position) } },
                        enabled = !frame.busy,
                        label = { Text(position.label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatermarkColours.forEach { (colour, label) ->
                    FilterChip(
                        selected = spec.color == colour,
                        onClick = { viewModel.edit { it.copy(color = colour) } },
                        enabled = !frame.busy,
                        label = { Text(label) },
                    )
                }
            }
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
        PdfBusyBar(frame = frame, onCancel = viewModel::cancel)
        PdfMessages(frame)
        ProtectionRow(selected = protection, enabled = !frame.busy, onSelect = viewModel::setProtection)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (frame.outputs.isNotEmpty()) {
                Button(onClick = { save.launch(watermarkedName(frame.name)) }, enabled = !frame.busy) { Text("Save PDF") }
            } else if (frame.isOpen) {
                Button(onClick = viewModel::apply, enabled = !frame.busy && pagesProblem == null) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun WatermarkPreview(spec: PdfWatermarkSpec, pagePreview: suspend (Int) -> Bitmap) {
    val page by produceState<ImageBitmap?>(null) { value = pagePreview(0).asImageBitmap() }
    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
        Canvas(Modifier.fillMaxSize()) {
            val image = page ?: return@Canvas
            val rect = fitRect(size, image.width.toFloat() / image.height)
            drawImage(image, IntOffset(rect.left.toInt(), rect.top.toInt()), IntSize(rect.width.toInt(), rect.height.toInt()))
            drawWatermarkPreview(rect, image.width.toFloat() / image.height, spec, measurer)
        }
    }
}

/** The largest rectangle of [aspect] (width over height) that fits in [container], centred. */
private fun fitRect(container: Size, aspect: Float): Rect {
    val width = min(container.width, container.height * aspect)
    val height = width / aspect
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun DrawScope.drawWatermarkPreview(rect: Rect, aspect: Float, spec: PdfWatermarkSpec, measurer: TextMeasurer) {
    val text = spec.text.trim()
    if (text.isEmpty()) return
    // Points on a US Letter width; the page is scaled to the preview rectangle.
    val pageWidth = 612f
    val pageHeight = pageWidth / aspect
    val scale = rect.width / pageWidth
    val size = spec.fontSize
    val textWidth = text.length * size * 0.5f
    val layout = measurer.measure(text, TextStyle(fontSize = (size * scale / density).sp))
    val tint = Color((0xFF shl 24) or spec.color).copy(alpha = spec.opacity)
    for (anchor in watermarkAnchors(spec.position, pageWidth, pageHeight, textWidth, size)) {
        val x = rect.left + anchor.first * scale
        val y = rect.top + anchor.second * scale
        withTransform({ rotate(-spec.angleDegrees, Offset(x, y)) }) {
            drawText(
                layout,
                tint,
                topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f),
            )
        }
    }
}

/**
 * Centres of the copies on a page of [width] by [height] points, in display coordinates
 * (origin top-left). This mirrors the engine's placement, so the preview matches the file.
 */
internal fun watermarkAnchors(
    position: PdfWatermarkPosition,
    width: Float,
    height: Float,
    textWidth: Float,
    size: Float,
): List<Pair<Float, Float>> {
    val margin = 0.04f * min(width, height)
    val halfWidth = textWidth / 2f
    val halfHeight = size / 2f
    return when (position) {
        PdfWatermarkPosition.Center -> listOf(width / 2f to height / 2f)
        PdfWatermarkPosition.TopLeft -> listOf(margin + halfWidth to margin + halfHeight)
        PdfWatermarkPosition.TopCenter -> listOf(width / 2f to margin + halfHeight)
        PdfWatermarkPosition.TopRight -> listOf(width - margin - halfWidth to margin + halfHeight)
        PdfWatermarkPosition.BottomLeft -> listOf(margin + halfWidth to height - margin - halfHeight)
        PdfWatermarkPosition.BottomCenter -> listOf(width / 2f to height - margin - halfHeight)
        PdfWatermarkPosition.BottomRight -> listOf(width - margin - halfWidth to height - margin - halfHeight)
        PdfWatermarkPosition.Tiled -> {
            val stepX = maxOf(textWidth, size) + size * 4f
            val stepY = size * 5f
            val columns = ceil(width / stepX).toInt() + 1
            val rows = ceil(height / stepY).toInt() + 1
            val centres = ArrayList<Pair<Float, Float>>()
            for (row in 0 until rows) {
                val offset = if (row % 2 == 1) stepX / 2f else 0f
                for (column in 0 until columns) {
                    if (centres.size >= PREVIEW_TILE_LIMIT) return centres
                    centres += (column * stepX + offset) to (row * stepY + stepY / 2f)
                }
            }
            centres
        }
    }
}

private const val PREVIEW_TILE_LIMIT = 400

private fun watermarkedName(name: String): String =
    name.removeSuffix(".pdf").ifBlank { "Document" } + " watermarked.pdf"
