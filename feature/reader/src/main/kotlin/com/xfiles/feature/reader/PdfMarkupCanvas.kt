package com.xfiles.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.glassBar
import com.xfiles.documents.PdfBox
import com.xfiles.documents.PdfMarkup
import com.xfiles.documents.PdfMarkupKind
import com.xfiles.documents.PdfPoint
import kotlin.math.abs

@Composable
internal fun PdfMarkupControls(
    mode: PdfMarkupKind,
    state: PdfMarkupState,
    onMode: (PdfMarkupKind) -> Unit,
    onUndo: () -> Unit,
    onPrepare: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().glassBar().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PdfMarkupKind.entries.forEach { item ->
            FilterChip(selected = item == mode, onClick = { onMode(item) }, enabled = !state.working, label = { Text(item.name) })
        }
        TextButton(onClick = onUndo, enabled = !state.working && state.marks.isNotEmpty()) { Text("Undo") }
        if (state.output == null) {
            TextButton(onClick = onPrepare, enabled = !state.working && state.marks.isNotEmpty()) { Text("Prepare copy") }
        } else {
            TextButton(onClick = onSave, enabled = !state.working) { Text("Save PDF") }
        }
    }
    if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.saved) Text("Annotated PDF saved.", color = EzColors.TextSecondary)
}

@Composable
internal fun PdfMarkupCanvas(
    bitmap: Bitmap,
    page: Int,
    mode: PdfMarkupKind,
    marks: List<PdfMarkup>,
    enabled: Boolean,
    onAdd: (PdfMarkup) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val pending = remember(page, mode) { mutableStateListOf<PdfPoint>() }
    val add by rememberUpdatedState(onAdd)
    var noteAnchor by remember(page) { mutableStateOf<PdfPoint?>(null) }
    val gesturing = if (!enabled) Modifier else if (mode == PdfMarkupKind.Note) {
        Modifier.pointerInput(bitmap, page) {
            detectTapGestures { offset ->
                val bounds = fitPdfPage(size.width.toFloat(), size.height.toFloat(), bitmap)
                noteAnchor = bounds.point(offset, clamp = false)
            }
        }
    } else {
        Modifier.pointerInput(bitmap, page, mode) {
            val bounds = fitPdfPage(size.width.toFloat(), size.height.toFloat(), bitmap)
            detectDragGestures(
                onDragStart = { start ->
                    pending.clear()
                    bounds.point(start, clamp = false)?.let(pending::add)
                },
                onDragEnd = {
                    val mark = draftMark(page, mode, pending)
                    pending.clear()
                    if (mark != null) add(mark)
                },
                onDragCancel = { pending.clear() },
            ) { change, _ ->
                if (pending.isNotEmpty()) {
                    val point = bounds.point(change.position, clamp = true)
                    if (point != null && point != pending.last()) {
                        if (mode == PdfMarkupKind.Highlight && pending.size == 2) {
                            pending[1] = point
                        } else if (pending.size < MAX_STROKE_POINTS) {
                            pending.add(point)
                        }
                    }
                    change.consume()
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().then(gesturing)) {
        Image(image, "Annotating page ${page + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize()) {
            val bounds = fitPdfPage(size.width, size.height, bitmap)
            marks.forEach { mark -> drawMarkup(bounds, mark) }
            draftMark(page, mode, pending)?.let { drawMarkup(bounds, it) }
        }
    }
    val anchor = noteAnchor
    if (anchor != null) {
        val input = rememberTextFieldState()
        AlertDialog(
            onDismissRequest = { noteAnchor = null },
            title = { Text("Add PDF note") },
            text = {
                OutlinedTextField(
                    state = input,
                    label = { Text("Note") },
                    inputTransformation = InputTransformation.maxLengthReject(2048),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = input.text.isNotBlank(),
                    onClick = {
                        add(PdfMarkup.Note(page, anchor, input.text.toString().trim()))
                        noteAnchor = null
                    },
                ) { Text("Add note") }
            },
            dismissButton = { TextButton(onClick = { noteAnchor = null }) { Text("Cancel") } },
        )
    }
}

private const val MAX_STROKE_POINTS = 1024

private fun draftMark(page: Int, kind: PdfMarkupKind, points: List<PdfPoint>): PdfMarkup? {
    if (points.size < 2) return null
    val start = points.first()
    val end = points.last()
    return when (kind) {
        PdfMarkupKind.Highlight -> {
            if (abs(end.x - start.x) < 0.001f || abs(end.y - start.y) < 0.001f) null
            else PdfMarkup.Highlight(page, listOf(PdfBox(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))))
        }
        PdfMarkupKind.Pen, PdfMarkupKind.Signature -> PdfMarkup.Ink(page, points.toList(), kind == PdfMarkupKind.Signature)
        PdfMarkupKind.Note -> null
    }
}

private data class PdfPageBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun point(offset: Offset, clamp: Boolean): PdfPoint? {
        if (width <= 0f || height <= 0f) return null
        val x = (offset.x - left) / width
        val y = (offset.y - top) / height
        if (!clamp && (x !in 0f..1f || y !in 0f..1f)) return null
        return PdfPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    fun offset(point: PdfPoint) = Offset(left + point.x * width, top + point.y * height)
}

private fun fitPdfPage(width: Float, height: Float, bitmap: Bitmap): PdfPageBounds {
    val scale = minOf(width / bitmap.width, height / bitmap.height)
    val pageWidth = bitmap.width * scale
    val pageHeight = bitmap.height * scale
    return PdfPageBounds((width - pageWidth) / 2, (height - pageHeight) / 2, pageWidth, pageHeight)
}

private fun DrawScope.drawMarkup(page: PdfPageBounds, mark: PdfMarkup) {
    when (mark) {
        is PdfMarkup.Highlight -> mark.boxes.forEach { box ->
            drawRect(
                Color(0x75FFC928),
                topLeft = page.offset(PdfPoint(box.left, box.top)),
                size = Size(page.width * (box.right - box.left), page.height * (box.bottom - box.top)),
            )
        }
        is PdfMarkup.Note -> drawCircle(Color(0xFFFFC928), radius = 8.dp.toPx(), center = page.offset(mark.anchor))
        is PdfMarkup.Ink -> {
            val start = page.offset(mark.points.first())
            val path = Path().apply {
                moveTo(start.x, start.y)
                for (index in 1 until mark.points.size) {
                    val point = page.offset(mark.points[index])
                    lineTo(point.x, point.y)
                }
            }
            drawPath(
                path,
                if (mark.signature) Color(0xFF273477) else Color(0xFFB90020),
                style = Stroke(width = if (mark.signature) 1.6.dp.toPx() else 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
