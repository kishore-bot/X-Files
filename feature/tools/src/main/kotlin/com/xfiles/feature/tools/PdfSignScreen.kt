package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.documents.MAX_SAVED_SIGNATURES
import com.xfiles.design.glassCard
import com.xfiles.documents.PdfSignStroke
import kotlin.math.abs

private val InkColour = Color(0xFF1A237E)
private const val REFERENCE_PAGE_WIDTH = 612f

/**
 * A drawn signature, placed on a page. The pad and the saved signatures are
 * visual only: they're drawn on the page as vector strokes, not signed with a key.
 */
@Composable
fun PdfSignScreen(
    onBack: () -> Unit,
    viewModel: PdfSignViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DedicatedToolHeader(title = "Sign", onBack = back)
        PdfSourceCard(
            frame = frame,
            onChoose = { choose.launch(arrayOf("application/pdf")) },
            onUnlock = { password -> pickedUri?.let { viewModel.open(Uri.parse(it), password) } },
        )
        if (frame.isOpen && frame.outputs.isEmpty()) {
            Text("Draw your signature", style = MaterialTheme.typography.titleSmall)
            SignaturePad(
                strokes = input.strokes,
                onStroke = viewModel::addStroke,
                modifier = Modifier.fillMaxWidth().aspectRatio(PdfSignViewModel.ASPECT),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = viewModel::undoStroke, enabled = input.strokes.isNotEmpty()) { Text("Undo") }
                TextButton(onClick = viewModel::clearPad, enabled = input.strokes.isNotEmpty()) { Text("Clear") }
                TextButton(
                    onClick = viewModel::saveCurrent,
                    enabled = input.strokes.isNotEmpty() && saved.size < MAX_SAVED_SIGNATURES,
                ) { Text("Keep for later") }
            }
            if (saved.isNotEmpty()) {
                Text("Saved (${saved.size} of $MAX_SAVED_SIGNATURES)", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    saved.forEachIndexed { index, signature ->
                        Column(
                            Modifier.glassCard().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Canvas(Modifier.size(width = 120.dp, height = 40.dp)) {
                                drawSignature(signature.strokes, InkColour, 1.5.dp.toPx())
                            }
                            Row {
                                TextButton(onClick = { viewModel.useSaved(index) }) { Text("Use") }
                                TextButton(onClick = { viewModel.deleteSaved(index) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
            Text("Place it on the page", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.setPage(input.page - 1) }, enabled = input.page > 0) { Text("Previous") }
                Text(
                    "Page ${input.page + 1} of ${frame.pageCount}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = { viewModel.setPage(input.page + 1) },
                    enabled = input.page < frame.pageCount - 1,
                ) { Text("Next") }
            }
            SignPlacement(viewModel = viewModel, input = input)
            Text(
                "Drag the signature to move it. Drag its corner to resize.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PdfBusyBar(frame = frame, onCancel = viewModel::cancel)
        PdfMessages(frame)
        ProtectionRow(selected = protection, enabled = !frame.busy, onSelect = viewModel::setProtection)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (frame.outputs.isNotEmpty()) {
                Button(onClick = { save.launch(signedName(frame.name)) }, enabled = !frame.busy) { Text("Save PDF") }
            } else if (frame.isOpen) {
                Button(onClick = viewModel::apply, enabled = !frame.busy && input.strokes.isNotEmpty()) { Text("Sign") }
            }
        }
    }
}

/** Pen input on a pad. Finished strokes are normalised to the pad, so they scale to any page. */
@Composable
private fun SignaturePad(
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
        drawSignature(strokes, InkColour, 3.dp.toPx())
        if (live.size > 1) drawPath(strokePath(live), InkColour, style = inkStyle(3.dp.toPx()))
    }
}

/**
 * The placement box over the page preview. Dragging inside it moves it; dragging
 * near its bottom-right corner resizes it. Coordinates go to the view model as
 * fractions, so the same numbers land on the page at any zoom.
 */
@Composable
private fun SignPlacement(viewModel: PdfSignViewModel, input: PdfSignInput) {
    val page by produceState<ImageBitmap?>(null, input.page) { value = viewModel.pagePreview(input.page).asImageBitmap() }
    LaunchedEffect(page) {
        page?.let { viewModel.setPageAspect(it.width.toFloat() / it.height) }
    }
    val current by rememberUpdatedState(input)
    var grip by remember { mutableStateOf(Grip.None) }
    Box(Modifier.fillMaxWidth().aspectRatio(input.pageAspect)) {
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
                InkColour.copy(alpha = 0.35f),
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = 1.dp.toPx()),
            )
            // Strokes are fractions of the placement box, so they map straight into it.
            val lineWidth = current.lineWidth * size.width / REFERENCE_PAGE_WIDTH
            current.strokes.forEach { stroke ->
                if (stroke.size >= 2) {
                    val mapped = stroke.map { (x, y) -> Offset(left + x * boxWidth, top + y * boxHeight) }
                    drawPath(strokePath(mapped), InkColour, style = inkStyle(lineWidth))
                }
            }
        }
    }
}

private enum class Grip { None, Move, Resize }

private const val HANDLE_PX = 40f

private fun DrawScope.drawSignature(strokes: List<PdfSignStroke>, color: Color, lineWidth: Float) {
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

private fun signedName(name: String): String =
    name.removeSuffix(".pdf").ifBlank { "Document" } + " signed.pdf"
