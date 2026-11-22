package com.xfiles.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.MutableState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.xfiles.core.documents.PdfMatchBox
import com.xfiles.core.documents.PdfPageText
import com.xfiles.design.EzIcons
import com.xfiles.documents.PdfBox
import com.xfiles.documents.book.wordRangeAt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Text selection on one PDF page. [load] reads the page's character geometry (cached and off the main thread),
 * [onHighlight] receives the selected text's boxes as a highlight mark, and [onSearch] the selected text as a query.
 */
internal class PdfTextSource(
    val page: Int,
    val load: suspend () -> PdfPageText?,
    val onHighlight: (List<PdfBox>) -> Unit,
    val onSearch: (String) -> Unit,
)

/** The selected characters [start] until [end] of the page's text, their copied [text] and their merged [boxes]. */
internal data class PdfRange(val start: Int, val end: Int, val text: String, val boxes: List<PdfMatchBox>)

/** How far outside a character's box a long press still counts as on it, in page fractions. */
private const val HIT_SLOP = 0.012f

private val HandleTouch = 44.dp
private val HandleRadius = 7.dp
private val HandleStem = 2.dp
private val BarGap = 10.dp

/**
 * Maps between page fractions and the reader's window: the page is fitted to the viewport, turned by [rotation]
 * quarter turns about the viewport centre, then scaled by [zoom] and moved by [offset]. These are the same
 * transforms the reader draws with, so a touch lands on the character it is drawn over at any zoom or turn.
 */
internal class PdfPageTransform(
    viewportWidth: Float,
    viewportHeight: Float,
    private val pageWidth: Float,
    private val pageHeight: Float,
    rotation: Int,
    zoom: Float,
    private val offset: Offset,
) {
    private val centreX = viewportWidth / 2f
    private val centreY = viewportHeight / 2f
    private val radians = Math.toRadians(rotation.toDouble())
    private val cosine = cos(radians).toFloat()
    private val sine = sin(radians).toFloat()

    /** Device pixels per page pixel, after the turn, at fit and zoom. Zero when the page has no size. */
    private val scale: Float = run {
        val turned = rotation % 180 != 0
        val w = if (turned) pageHeight else pageWidth
        val h = if (turned) pageWidth else pageHeight
        if (w <= 0f || h <= 0f) 0f else minOf(viewportWidth / w, viewportHeight / h) * zoom
    }

    /** The window position of page fraction ([x], [y]). */
    fun pageToWindow(x: Float, y: Float): Offset {
        val dx = (x - 0.5f) * pageWidth
        val dy = (y - 0.5f) * pageHeight
        return Offset(
            centreX + offset.x + scale * (dx * cosine - dy * sine),
            centreY + offset.y + scale * (dx * sine + dy * cosine),
        )
    }

    /** The page fraction under the window position [point]; the page centre when the page has no size. */
    fun windowToPage(point: Offset): Offset {
        if (scale <= 0f) return Offset(0.5f, 0.5f)
        val ux = point.x - centreX - offset.x
        val uy = point.y - centreY - offset.y
        val dx = (ux * cosine + uy * sine) / scale
        val dy = (-ux * sine + uy * cosine) / scale
        return Offset(dx / pageWidth + 0.5f, dy / pageHeight + 0.5f)
    }
}

/**
 * The selection on one page: a long press selects the word under the finger, a drag extends it word by word,
 * and the two handles move either end. The range is an immutable [PdfRange] in [range]; the drawing reads it
 * in the draw phase. Ranges never cross pages, and a new page's selection starts empty.
 */
internal class PdfTextSelection(private val haptics: HapticFeedback) {
    /** The page's geometry once read; null until the first long press. */
    var geometry: PdfPageText? = null

    val range: MutableState<PdfRange?> = mutableStateOf(null)

    /** True while a long press or a handle moves the selection; the page's pan and zoom stand still meanwhile. */
    var dragging: Boolean = false
        private set

    private var anchorStart = 0
    private var anchorEnd = 0

    /**
     * A long press at page fraction ([x], [y]) selects the word under it. False when no word is there.
     * Needs [geometry] to be set.
     */
    fun begin(x: Float, y: Float): Boolean {
        val page = geometry ?: return false
        val index = nearestIndex(page, x, y) ?: return false
        val word = wordRangeAt(page.text, index) ?: return false
        anchorStart = word.first
        anchorEnd = word.last + 1
        dragging = true
        publish(anchorStart, anchorEnd, haptic = HapticFeedbackType.LongPress)
        return range.value != null
    }


    /** A drag from the long press reaches page fraction ([x], [y]): the selection grows word by word, either way. */
    fun extendTo(x: Float, y: Float) {
        val page = geometry ?: return
        if (anchorEnd <= anchorStart || !dragging) return
        val index = page.indexAt(x, y)
        if (index < 0) return
        val text = page.text
        when {
            index >= anchorEnd -> publish(anchorStart, snapEnd(text, index))
            index < anchorStart -> publish(snapStart(text, index), anchorEnd)
            else -> publish(anchorStart, anchorEnd)
        }
    }

    /** Starts a handle drag: pan and zoom wait until [endDrag]. */
    fun beginHandle() {
        dragging = true
    }

    /** Moves the start handle, or the end one, to page fraction ([x], [y]), snapped to words and never past the other. */
    fun moveHandle(startHandle: Boolean, x: Float, y: Float) {
        val current = range.value ?: return
        val page = geometry ?: return
        val index = page.indexAt(x, y)
        if (index < 0) return
        val text = page.text
        if (startHandle) {
            publish(min(snapStart(text, index), current.end - 1).coerceAtLeast(0), current.end)
        } else {
            publish(current.start, max(snapEnd(text, index), current.start + 1).coerceAtMost(text.length))
        }
    }

    fun endDrag() {
        dragging = false
    }

    /** Drops the selection; the geometry stays, so the next long press on this page is instant. */
    fun clear() {
        dragging = false
        anchorStart = 0
        anchorEnd = 0
        if (range.value != null) range.value = null
    }

    /** Sets the selection to characters [start] until [end]. Blank selections are ignored, so a handle over spaces holds. */
    private fun publish(start: Int, end: Int, haptic: HapticFeedbackType = HapticFeedbackType.TextHandleMove) {
        val page = geometry ?: return
        val current = range.value
        if (current != null && current.start == start && current.end == end) return
        val boxes = page.boxesBetween(start, end)
        val text = page.text.substring(start, end).trim()
        if (text.isEmpty() || boxes.isEmpty()) return
        haptics.performHapticFeedback(haptic)
        range.value = PdfRange(start, end, text, boxes)
    }

    private fun nearestIndex(page: PdfPageText, x: Float, y: Float): Int? {
        val index = page.indexAt(x, y)
        if (index < 0) return null
        val box = page.boxOf(index) ?: return null
        val inside = x >= box.left - HIT_SLOP && x <= box.right + HIT_SLOP &&
            y >= box.top - HIT_SLOP && y <= box.bottom + HIT_SLOP
        return index.takeIf { inside }
    }

    private fun snapStart(text: String, index: Int): Int = wordRangeAt(text, index)?.first ?: index
    private fun snapEnd(text: String, index: Int): Int = wordRangeAt(text, index)?.let { it.last + 1 } ?: (index + 1)
}

/**
 * The selection's handles and action bar, over the page in window coordinates. Handles are placed in the layout
 * phase and draw in the draw phase, so a drag moves them without recomposing. [transform] is read at each use.
 */
@Composable
internal fun PdfSelectionOverlay(
    selection: PdfTextSelection,
    source: PdfTextSource,
    transform: () -> PdfPageTransform,
    color: Color,
    onClear: () -> Unit,
) {
    val hasSelection by remember(selection) { derivedStateOf { selection.range.value != null } }
    Box(Modifier.fillMaxSize()) {
        PdfHandle(startHandle = true, selection, transform, color)
        PdfHandle(startHandle = false, selection, transform, color)
        if (hasSelection) PdfSelectionBarHost(selection, transform) {
            PdfSelectionActions(selection, source, color, onClear)
        }
    }
}

@Composable
private fun PdfHandle(startHandle: Boolean, selection: PdfTextSelection, transform: () -> PdfPageTransform, color: Color) {
    val density = LocalDensity.current
    val touchPx = with(density) { HandleTouch.toPx() }
    val radiusPx = with(density) { HandleRadius.toPx() }
    val stemPx = with(density) { HandleStem.toPx() }
    Canvas(
        Modifier
            .size(HandleTouch)
            .offset {
                // Layout phase: reads the range and the transform, so the handle follows its word.
                val anchor = anchorPoint(selection, startHandle, transform)
                if (anchor == null) {
                    IntOffset(-touchPx.roundToInt() * 4, -touchPx.roundToInt() * 4)
                } else {
                    IntOffset((anchor.x - touchPx / 2f).roundToInt(), (anchor.y + radiusPx - touchPx / 2f).roundToInt())
                }
            }
            .pointerInput(startHandle) {
                var finger = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        finger = anchorPoint(selection, startHandle, transform) ?: Offset.Zero
                        selection.beginHandle()
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        finger += delta
                        val page = transform().windowToPage(finger)
                        selection.moveHandle(startHandle, page.x, page.y)
                    },
                    onDragEnd = { selection.endDrag() },
                    onDragCancel = { selection.endDrag() },
                )
            },
    ) {
        // Draw phase: the range is read here, so a handle moving costs no recomposition.
        val boxes = selection.range.value?.boxes ?: return@Canvas
        val box = if (startHandle) boxes.first() else boxes.last()
        if (box.right <= box.left) return@Canvas
        val centre = Offset(size.width / 2f, size.height / 2f)
        val caretBottom = touchPx / 2f - radiusPx
        drawRect(color, topLeft = Offset(centre.x - stemPx / 2f, caretBottom), size = Size(stemPx, centre.y - caretBottom))
        drawCircle(color, radius = radiusPx, center = centre)
    }
}

/** The window point where a handle hangs: the bottom of the first character (start) or the last (end). */
private fun anchorPoint(selection: PdfTextSelection, startHandle: Boolean, transform: () -> PdfPageTransform): Offset? {
    val boxes = selection.range.value?.boxes ?: return null
    val box = if (startHandle) boxes.first() else boxes.last()
    val x = if (startHandle) box.left else box.right
    return transform().pageToWindow(x, box.bottom)
}

/** Places the action bar above the selection's start, or below its handle when there is no room. Hidden with no selection. */
@Composable
private fun PdfSelectionBarHost(selection: PdfTextSelection, transform: () -> PdfPageTransform, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val gapPx = with(density) { BarGap.toPx() }
    val handlePx = with(density) { HandleTouch.toPx() }
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            val bar = placeable ?: return@layout
            // Placement phase: the range is read here, so a drag moves the bar without recomposing.
            val first = selection.range.value?.boxes?.firstOrNull() ?: return@layout
            val p = transform()
            val top = p.pageToWindow(first.left, first.top)
            val bottom = p.pageToWindow(first.left, first.bottom)
            val caret = Rect(top.x, min(top.y, bottom.y), top.x, max(top.y, bottom.y))
            val at = barOrigin(caret, bar.width.toFloat(), bar.height.toFloat(), width.toFloat(), height.toFloat(), gapPx, handlePx)
            bar.place(at.x.roundToInt(), at.y.roundToInt())
        }
    }
}

/** Copy, Highlight, Search, Share and close for the selection. Each acts on the range at the time it is pressed. */
@Composable
private fun PdfSelectionActions(selection: PdfTextSelection, source: PdfTextSource, color: Color, onClear: () -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = {
                selection.range.value?.let { copyText(context, it.text) }
                onClear()
            }) { Text("Copy") }
            TextButton(onClick = {
                selection.range.value?.let { r -> source.onHighlight(r.boxes.map { PdfBox(it.left, it.top, it.right, it.bottom) }) }
                onClear()
            }) { Text("Highlight") }
            TextButton(onClick = {
                selection.range.value?.let { source.onSearch(it.text) }
                onClear()
            }) { Text("Search") }
            IconButton(onClick = { selection.range.value?.let { shareText(context, it.text) } }) {
                Icon(EzIcons.Share, "Share selected text", tint = color)
            }
            IconButton(onClick = onClear) { Icon(EzIcons.Close, "Clear selection", tint = color) }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Selected text", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, null))
}
