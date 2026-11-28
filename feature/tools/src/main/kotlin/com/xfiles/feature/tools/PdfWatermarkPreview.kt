package com.xfiles.feature.tools

import androidx.compose.runtime.getValue
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.EzShapes
import com.xfiles.design.rememberHaptics
import com.xfiles.design.glassCard
import com.xfiles.documents.PdfWatermarkPosition
import com.xfiles.documents.PdfWatermarkSpec
import kotlin.math.ceil
import kotlin.math.min

/**
 * The first page with the watermark drawn over it, using the same placement the engine uses, so the preview is close
 * to the saved file. It redraws as the spec changes.
 */
@Composable
internal fun WatermarkPreview(
    spec: PdfWatermarkSpec,
    pagePreview: suspend (Int) -> Bitmap,
    modifier: Modifier = Modifier,
) {
    val page by produceState<ImageBitmap?>(null) { value = loadOrNull { pagePreview(0) }?.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .glassCard(EzShapes.Card, 0.5f)
            .padding(10.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val image = page
            if (image == null) return@Canvas
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
 * Centres of the copies on a page of [width] by [height] points, in display coordinates (origin top-left). This mirrors
 * the engine's placement, so the preview matches the file.
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

/**
 * Where the watermark sits, as a 3 by 3 picker. The engine has no left or right middle position, so those two cells are
 * left empty; the centre cell is [PdfWatermarkPosition.Center].
 */
@Composable
internal fun WatermarkPositionPicker(
    selected: PdfWatermarkPosition,
    enabled: Boolean,
    onSelect: (PdfWatermarkPosition) -> Unit,
) {
    val rows: List<List<PdfWatermarkPosition?>> = listOf(
        listOf(PdfWatermarkPosition.TopLeft, PdfWatermarkPosition.TopCenter, PdfWatermarkPosition.TopRight),
        listOf(null, PdfWatermarkPosition.Center, null),
        listOf(PdfWatermarkPosition.BottomLeft, PdfWatermarkPosition.BottomCenter, PdfWatermarkPosition.BottomRight),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { position ->
                    if (position == null) {
                        Box(Modifier.size(52.dp))
                    } else {
                        PositionCell(position, selected == position, enabled, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionCell(
    position: PdfWatermarkPosition,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (PdfWatermarkPosition) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val align = when (position) {
        PdfWatermarkPosition.TopLeft -> Alignment.TopStart
        PdfWatermarkPosition.TopCenter -> Alignment.TopCenter
        PdfWatermarkPosition.TopRight -> Alignment.TopEnd
        PdfWatermarkPosition.BottomLeft -> Alignment.BottomStart
        PdfWatermarkPosition.BottomCenter -> Alignment.BottomCenter
        PdfWatermarkPosition.BottomRight -> Alignment.BottomEnd
        else -> Alignment.Center
    }
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) EzColors.Primary else EzColors.Selected)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
            ) {
                haptics.tick()
                onSelect(position)
            }
            .padding(9.dp),
        contentAlignment = align,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) EzColors.OnPrimary else EzColors.TextSecondary),
        )
    }
}
