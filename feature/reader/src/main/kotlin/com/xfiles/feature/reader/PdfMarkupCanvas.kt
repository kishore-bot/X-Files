package com.xfiles.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.graphics.ColorFilter
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
import com.xfiles.design.EzControlBar
import com.xfiles.design.EzIcons
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
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canEdit = !state.working
    val canUndo = canEdit && state.marks.isNotEmpty()
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = colors.error, modifier = Modifier.padding(horizontal = 12.dp)) }
        if (state.saved) Text("Annotated PDF saved.", color = EzColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp))
        EzControlBar {
            PdfMarkupKind.entries.forEach { item ->
                val icon = when (item) {
                    PdfMarkupKind.Pen -> EzIcons.Edit
                    PdfMarkupKind.Highlight -> EzIcons.Highlight
                    PdfMarkupKind.Note -> EzIcons.NoteAdd
                    PdfMarkupKind.Signature -> EzIcons.Signature
                }
                PdfMarkupTooltip(item.name, Modifier.weight(1f)) {
                    IconToggleButton(
                        checked = item == mode,
                        onCheckedChange = { if (it) onMode(item) },
                        enabled = canEdit,
                        colors = IconButtonDefaults.iconToggleButtonColors(
                            checkedContainerColor = colors.primaryContainer,
                            checkedContentColor = colors.onPrimaryContainer,
                        ),
                    ) { Icon(icon, item.name) }
                }
            }
            PdfMarkupTooltip("Undo", Modifier.weight(1f)) {
                IconButton(onClick = onUndo, enabled = canUndo) { Icon(EzIcons.Undo, "Undo") }
            }
            val saveLabel = "Save annotated PDF"
            PdfMarkupTooltip(saveLabel, Modifier.weight(1f)) {
                IconButton(
                    onClick = onSave,
                    enabled = canEdit && (state.output != null || state.marks.isNotEmpty()),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                    ),
                ) { Icon(EzIcons.SaveCopy, saveLabel) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfMarkupTooltip(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            content = content,
        )
    }
}

@Composable
internal fun PdfMarkupCanvas(
    bitmap: Bitmap,
    page: Int,
    mode: PdfMarkupKind,
    marks: List<PdfMarkup>,
    enabled: Boolean,
    onAdd: (PdfMarkup) -> Unit,
    colorFilter: ColorFilter?,
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
                    if (point != null && point != pending.lastOrNull()) {
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
        Image(image, "Annotating page ${page + 1}", Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Fit, colorFilter = colorFilter)
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
    return PdfPageBounds((width - pageWidth) / 2, 0f, pageWidth, pageHeight)
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
            val firstPoint = mark.points.firstOrNull() ?: return
            val start = page.offset(firstPoint)
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
