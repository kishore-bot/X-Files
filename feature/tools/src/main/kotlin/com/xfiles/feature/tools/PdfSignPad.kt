package com.xfiles.feature.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xfiles.design.glassCard
import com.xfiles.documents.PdfSignStroke
import kotlin.math.abs

internal val SignInk = Color(0xFF1A237E)
private const val REFERENCE_PAGE_WIDTH = 612f
private const val HANDLE_PX = 40f

/** Pen input on a pad. Finished strokes are normalised to the pad, so they scale to any page. */
@Composable
internal fun SignaturePad(
    strokes: List<PdfSignStroke>,
    onStroke: (PdfSignStroke) -> Unit,
    modifier: Modifier,
) {
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Canvas(
        modifier
            .glassCard()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start -> live = listOf(start) },
                    onDrag = { change, _ ->
                        change.consume()
                        live = live + change.position
                    },
                    onDragEnd = {
                        val points = live
                        live = emptyList()
                        if (points.size > 1) onStroke(normalise(points, size.width.toFloat(), size.height.toFloat()))
                    },
                    onDragCancel = { live = emptyList() },
                )
            },
    ) {
        drawSignature(strokes, SignInk, 3.dp.toPx())
        if (live.size > 1) drawPath(strokePath(live), SignInk, style = inkStyle(3.dp.toPx()))
    }
}

/** A saved signature drawn small, for the row of kept signatures. */
@Composable
internal fun SignatureThumb(strokes: List<PdfSignStroke>, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 120.dp, height = 44.dp)) {
        drawSignature(strokes, SignInk, 1.5.dp.toPx())
    }
}

/**
 * The placement box over the page preview. Dragging inside it moves it; dragging near its bottom-right corner resizes
 * it. Coordinates go to the view model as fractions, so the same numbers land on the page at any zoom.
 */
@Composable
internal fun SignPlacement(viewModel: PdfSignViewModel, input: PdfSignInput) {
    val page by produceState<ImageBitmap?>(null, input.page) {
        value = loadOrNull { viewModel.pagePreview(input.page) }?.asImageBitmap()
    }
    LaunchedEffect(page) {
        page?.let { viewModel.setPageAspect(it.width.toFloat() / it.height) }
    }
    val current by rememberUpdatedState(input)
    var grip by remember { mutableStateOf(Grip.None) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(input.pageAspect)
            .glassCard(com.xfiles.design.EzShapes.Card, 0.4f),
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        val box = current
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val right = (box.x + box.width) * w
                        val bottom = (box.y + box.height) * h
                        grip = when {
                            abs(start.x - right) < HANDLE_PX && abs(start.y - bottom) < HANDLE_PX -> Grip.Resize
                            start.x in box.x * w..right && start.y in box.y * h..bottom -> Grip.Move
                            else -> Grip.None
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        when (grip) {
                            Grip.Move -> viewModel.moveBy(amount.x / w, amount.y / h)
                            Grip.Resize -> viewModel.resizeBy(amount.x / w)
                            Grip.None -> Unit
                        }
                    },
                    onDragEnd = { grip = Grip.None },
                    onDragCancel = { grip = Grip.None },
                )
            },
        ) {
            val image = page
            val pageSize = IntSize(size.width.toInt(), size.height.toInt())
            if (image != null) drawImage(image, IntOffset.Zero, pageSize)
            val left = current.x * size.width
            val top = current.y * size.height
            val boxWidth = current.width * size.width
            val boxHeight = current.height * size.height
            drawRect(
                SignInk.copy(alpha = 0.35f),
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = 1.dp.toPx()),
            )
            // Strokes are fractions of the placement box, so they map straight into it.
            val lineWidth = current.lineWidth * size.width / REFERENCE_PAGE_WIDTH
            current.strokes.forEach { stroke ->
                if (stroke.size >= 2) {
                    val mapped = stroke.map { (x, y) -> Offset(left + x * boxWidth, top + y * boxHeight) }
                    drawPath(strokePath(mapped), SignInk, style = inkStyle(lineWidth))
                }
            }
        }
    }
}

private enum class Grip { None, Move, Resize }

internal fun DrawScope.drawSignature(strokes: List<PdfSignStroke>, color: Color, lineWidth: Float) {
    strokes.forEach { stroke ->
        if (stroke.size < 2) return@forEach
        drawPath(
            strokePath(stroke.map { (x, y) -> Offset(x * size.width, y * size.height) }),
            color,
            style = inkStyle(lineWidth),
        )
    }
}

private fun strokePath(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { index, point ->
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
}

private fun inkStyle(width: Float): Stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun normalise(points: List<Offset>, width: Float, height: Float): PdfSignStroke =
    points.map { point -> (point.x / width).coerceIn(0f, 1f) to (point.y / height).coerceIn(0f, 1f) }
