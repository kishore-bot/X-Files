package com.xfiles.feature.reader

import androidx.compose.ui.layout.positionInWindow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xfiles.design.BarIconButton
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.rememberHaptics
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import com.xfiles.documents.book.HighlightColor
import com.xfiles.documents.book.TextPoint
import com.xfiles.documents.book.wordEndAt
import com.xfiles.documents.book.wordRangeAt
import com.xfiles.documents.book.wordStartAt
import kotlin.math.roundToInt

/** A selection: its first and last points in reading order, with start <= end. Immutable. */
internal data class LiveSelection(val start: TextPoint, val end: TextPoint)

/** One text block on screen: where it is, its laid-out text, and the text. */
internal class TextProbe(val coords: LayoutCoordinates, val layout: TextLayoutResult, val text: String)

/** Distance from a zone of the reading area to the edges of a drag: how close the finger is to the top or bottom edge. */
internal val EdgeZone = 48.dp

/** Scroll speed at the very edge of the reading area, px/s scaled by depth into [EdgeZone]. */
internal val EdgeMaxSpeed = 1200.dp

private val HandleTouch = 44.dp
private val HandleRadius = 7.dp
private val HandleStem = 2.dp
private val MagnifierSize = DpSize(96.dp, 48.dp)
private val BarGap = 10.dp
private const val BAR_ANIM_MS = 160
private const val NO_OWNER = -1

/**
 * The text blocks on screen, so a point on screen maps to a block and an offset in it. Only the
 * blocks composed now are here, keyed by their global block index. Main thread only.
 */
internal class TextProbes {
    private val byBlock = HashMap<Int, TextProbe>()

    /** Changes when a block's text is laid out or leaves the screen, or the text moves, so what is drawn over it is placed again. */
    var revision by mutableIntStateOf(0)
        private set

    fun put(block: Int, probe: TextProbe) {
        byBlock[block] = probe
        revision++
    }

    fun remove(block: Int) {
        if (byBlock.remove(block) != null) revision++
    }

    /** Asks what is drawn over the text to be placed again, e.g. after a scroll moved the blocks. */
    fun invalidate() {
        revision++
    }

    fun textOf(block: Int): String? = byBlock[block]?.text

    /**
     * The point under the window position [window]: the block whose rectangle is nearest it (zero
     * distance when it is inside), and the offset in that block. Linear in the blocks on screen,
     * which is a few dozen at most.
     */
    fun pointAt(window: Offset): TextPoint? {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for ((block, probe) in byBlock) {
            val coords = probe.coords
            if (!coords.isAttached) continue
            val origin = coords.positionInWindow()
            val distance = distanceToRect(window, origin.x, origin.y, origin.x + coords.size.width, origin.y + coords.size.height)
            if (distance < bestDistance || (distance == bestDistance && block < best)) {
                best = block
                bestDistance = distance
            }
        }
        if (best < 0) return null
        val probe = byBlock.getValue(best)
        val offset = probe.layout.getOffsetForPosition(probe.coords.windowToLocal(window))
        return TextPoint(best, offset.coerceIn(0, probe.text.length))
    }

    /** The caret at [point] as a zero-width window rectangle from the top to the bottom of the line, or null if its block is not on screen. */
    fun caretWindow(point: TextPoint): Rect? {
        val probe = byBlock[point.block] ?: return null
        if (!probe.coords.isAttached) return null
        val rect = probe.layout.getCursorRect(point.offset.coerceIn(0, probe.text.length))
        val top = probe.coords.localToWindow(Offset(rect.left, rect.top))
        val bottom = probe.coords.localToWindow(Offset(rect.left, rect.bottom))
        return Rect(top.x, top.y, top.x, bottom.y)
    }
}

/** Distance from [x], [y] to the rectangle, zero when inside. Edges outside it add up. */
private fun distanceToRect(point: Offset, left: Float, top: Float, right: Float, bottom: Float): Float {
    val dx = when {
        point.x < left -> left - point.x
        point.x > right -> point.x - right
        else -> 0f
    }
    val dy = when {
        point.y < top -> top - point.y
        point.y > bottom -> point.y - bottom
        else -> 0f
    }
    return dx + dy
}

private enum class DragMode { Word, StartHandle, EndHandle }

/**
 * Owns the live selection: a long press starts it on a word, a drag extends it word by word
 * (across blocks, and pages), and the two handles move either end. Each change replaces the
 * immutable [LiveSelection]. While a drag is on, the last finger position is kept, so the text can
 * move under it (auto-scroll, a page turn) and the selection is placed again with [refresh].
 */
internal class BookSelectionController(
    val probes: TextProbes,
    private val state: MutableState<LiveSelection?>,
    private val haptics: HapticFeedback,
) {
    val selection: LiveSelection?
        get() = state.value

    /** The reading area in window coordinates. Edge zones and the action bar are measured from it. */
    var viewport: Rect = Rect.Zero

    /** True while a long press or a handle is moving the selection. The per-frame loops run only while it is. */
    var dragging by mutableStateOf(false)
        private set

    private var mode = DragMode.Word
    private var anchorStart: TextPoint? = null
    private var anchorEnd: TextPoint? = null
    private var finger: Offset? = null

    /** The block whose gesture started the current drag, so its disposal can end the drag. */
    private var dragOwner = NO_OWNER

    /** A long press at [window], in block [owner], selects the word under it. */
    fun begin(window: Offset, owner: Int) {
        val point = probes.pointAt(window) ?: return
        val text = probes.textOf(point.block) ?: return
        val range = wordRangeAt(text, point.offset) ?: return
        val start = TextPoint(point.block, range.first)
        val end = TextPoint(point.block, range.last + 1)
        anchorStart = start
        anchorEnd = end
        mode = DragMode.Word
        dragOwner = owner
        finger = window
        dragging = true
        state.value = LiveSelection(start, end)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** A drag from the long press reaches [window]: the selection grows word by word, forward or back. */
    fun extendTo(window: Offset) {
        if (anchorStart == null) return
        mode = DragMode.Word
        finger = window
        applyDrag()
    }

    /** Moves the start handle ([startHandle]) or the end handle to [window], snapped to a word, never past the other handle. */
    fun moveHandle(startHandle: Boolean, window: Offset) {
        if (state.value == null) return
        mode = if (startHandle) DragMode.StartHandle else DragMode.EndHandle
        dragOwner = NO_OWNER
        finger = window
        dragging = true
        applyDrag()
    }

    fun endDrag() {
        finger = null
        dragOwner = NO_OWNER
        dragging = false
    }

    /** Ends the drag if block [owner] started it, e.g. when that block leaves the composition. */
    fun release(owner: Int) {
        if (dragOwner == owner) endDrag()
    }

    /** Places the selection again at the finger, after the text under it moved. Does nothing without a drag. */
    fun refresh() {
        if (finger != null) applyDrag()
    }

    /** Auto-scroll speed for the finger in px/s: negative near the top, positive near the bottom, 0 elsewhere. */
    fun verticalSpeed(zonePx: Float, maxSpeedPx: Float): Float {
        val f = finger ?: return 0f
        if (viewport.isEmpty) return 0f
        return edgeSpeed(f.y, viewport.top, viewport.bottom, zonePx, maxSpeedPx)
    }

    /** -1 while the finger is in the left edge zone, 1 in the right one, else 0. */
    fun horizontalEdge(zonePx: Float): Int {
        val f = finger ?: return 0
        if (viewport.isEmpty) return 0
        return when {
            f.x < viewport.left + zonePx -> -1
            f.x > viewport.right - zonePx -> 1
            else -> 0
        }
    }

    fun clear() {
        anchorStart = null
        anchorEnd = null
        finger = null
        dragOwner = NO_OWNER
        dragging = false
        if (state.value != null) state.value = null
    }

    private fun applyDrag() {
        val f = finger ?: return
        val next = when (mode) {
            DragMode.Word -> wordDrag(f)
            DragMode.StartHandle -> handleDrag(startHandle = true, window = f)
            DragMode.EndHandle -> handleDrag(startHandle = false, window = f)
        } ?: return
        publish(next)
    }

    private fun wordDrag(window: Offset): LiveSelection? {
        val anchorFirst = anchorStart ?: return null
        val anchorLast = anchorEnd ?: return null
        val point = probes.pointAt(window) ?: return null
        val text = probes.textOf(point.block) ?: return null
        return when {
            point > anchorLast -> LiveSelection(anchorFirst, TextPoint(point.block, wordEndAt(text, point.offset)))
            point < anchorFirst -> LiveSelection(TextPoint(point.block, wordStartAt(text, point.offset)), anchorLast)
            else -> LiveSelection(anchorFirst, anchorLast)
        }
    }

    private fun handleDrag(startHandle: Boolean, window: Offset): LiveSelection? {
        val current = state.value ?: return null
        val point = probes.pointAt(window) ?: return null
        val text = probes.textOf(point.block) ?: return null
        return if (startHandle) {
            LiveSelection(minOf(TextPoint(point.block, wordStartAt(text, point.offset)), current.end), current.end)
        } else {
            LiveSelection(current.start, maxOf(TextPoint(point.block, wordEndAt(text, point.offset)), current.start))
        }
    }

    /** Sets [next], with a handle-move haptic when either end moved to another word. */
    private fun publish(next: LiveSelection) {
        val old = state.value
        if (old == next) return
        if (old == null || old.start != next.start || old.end != next.end) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        state.value = next
    }
}

/**
 * Auto-scroll speed for a finger at [y] between [top] and [bottom]: full [maxSpeedPx] at the edge,
 * falling off linearly to zero at [zonePx] inside it. Negative towards the top.
 */
internal fun edgeSpeed(y: Float, top: Float, bottom: Float, zonePx: Float, maxSpeedPx: Float): Float {
    if (zonePx <= 0f) return 0f
    return when {
        y < top + zonePx -> -maxSpeedPx * ((top + zonePx - y) / zonePx).coerceIn(0f, 1f)
        y > bottom - zonePx -> maxSpeedPx * ((y - (bottom - zonePx)) / zonePx).coerceIn(0f, 1f)
        else -> 0f
    }
}

/**
 * Where the action bar goes, in the reading area's coordinates: centred over the start caret, above
 * it, or below the start handle when there is no room above. Clamped to the area.
 */
internal fun barOrigin(caret: Rect, barW: Float, barH: Float, areaW: Float, areaH: Float, gapPx: Float, handlePx: Float): Offset {
    val x = (caret.left - barW / 2f).coerceIn(0f, (areaW - barW).coerceAtLeast(0f))
    val above = caret.top - gapPx - barH
    val y = if (above >= 0f) above else (caret.bottom + handlePx).coerceIn(0f, (areaH - barH).coerceAtLeast(0f))
    return Offset(x, y)
}

/**
 * Runs [step] once per display frame until cancelled, with the frame's time in nanoseconds and the
 * seconds since the previous frame (0 on the first). Frame-synchronised, so motion is smooth and stops with its coroutine.
 */
internal suspend fun frameLoop(step: suspend (frameNanos: Long, dtSeconds: Float) -> Unit) {
    var last = 0L
    while (true) {
        val now = withFrameNanos { it }
        val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
        last = now
        step(now, dt)
    }
}

/**
 * The selection's handles, drawn over the reading area with a magnifier above the finger while one
 * is dragged. Draws nothing without a selection, and takes no touches except on the handles.
 * The reading area is the overlay, so window positions map to it by [BookSelectionController.viewport].
 */
@Composable
internal fun SelectionOverlay(controller: BookSelectionController, handleColor: Color, modifier: Modifier = Modifier) {
    val selection = controller.selection ?: return
    var finger by remember { mutableStateOf<Offset?>(null) }
    val drag = finger

    Box(
        modifier
            .fillMaxSize()
            .then(
                if (drag == null) {
                    Modifier
                } else {
                    Modifier.magnifier(
                        sourceCenter = { drag - controller.viewport.topLeft },
                        magnifierCenter = {
                            val local = drag - controller.viewport.topLeft
                            Offset(local.x, local.y - 64.dp.toPx())
                        },
                        zoom = 1.4f,
                        size = MagnifierSize,
                        cornerRadius = 24.dp,
                    )
                },
            ),
    ) {
        SelectionHandle(true, selection.start, controller, handleColor) { finger = it }
        SelectionHandle(false, selection.end, controller, handleColor) { finger = it }
    }
}

@Composable
private fun SelectionHandle(
    startHandle: Boolean,
    point: TextPoint,
    controller: BookSelectionController,
    color: Color,
    onFinger: (Offset?) -> Unit,
) {
    val density = LocalDensity.current
    val touchPx = with(density) { HandleTouch.toPx() }
    val radiusPx = with(density) { HandleRadius.toPx() }
    val stemPx = with(density) { HandleStem.toPx() }
    var own by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Canvas(
        Modifier
            .size(HandleTouch)
            .offset {
                // Reads the probes' revision, so the handle is placed again when the text is laid out or scrolled.
                controller.probes.revision
                val caret = controller.probes.caretWindow(point)
                if (caret == null) {
                    IntOffset(-touchPx.roundToInt() * 4, -touchPx.roundToInt() * 4)
                } else {
                    // The handle's teardrop hangs below the caret, its centre one radius under the caret's bottom.
                    val origin = controller.viewport.topLeft
                    IntOffset(
                        (caret.left - origin.x - touchPx / 2f).roundToInt(),
                        (caret.bottom - origin.y + radiusPx - touchPx / 2f).roundToInt(),
                    )
                }
            }
            .onGloballyPositioned { own = it }
            .pointerInput(startHandle) {
                var finger = Offset.Zero
                detectDragGestures(
                    onDragStart = { local ->
                        finger = own?.localToWindow(local) ?: finger
                        onFinger(finger)
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        finger += delta
                        controller.moveHandle(startHandle, finger)
                        onFinger(finger)
                    },
                    onDragEnd = {
                        controller.endDrag()
                        onFinger(null)
                    },
                    onDragCancel = {
                        controller.endDrag()
                        onFinger(null)
                    },
                )
            },
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val caretBottom = touchPx / 2f - radiusPx
        drawRect(
            color,
            topLeft = Offset(centre.x - stemPx / 2f, caretBottom),
            size = Size(stemPx, centre.y - caretBottom),
        )
        drawCircle(color, radius = radiusPx, center = centre)
    }
}

/**
 * Places [content] (the action bar) beside the selection: centred above its start, or below the start
 * handle when there is no room above. It fades and scales in and out. Its position is read in the
 * placement pass, so a scroll or a drag moves it without recomposing. Hidden while the start is off screen.
 */
@Composable
internal fun SelectionBarHost(controller: BookSelectionController, visible: Boolean, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val gapPx = with(density) { BarGap.toPx() }
    val handlePx = with(density) { HandleTouch.toPx() }
    Layout(
        content = {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(BAR_ANIM_MS)) + scaleIn(tween(BAR_ANIM_MS), initialScale = 0.92f),
                exit = fadeOut(tween(BAR_ANIM_MS)) + scaleOut(tween(BAR_ANIM_MS), targetScale = 0.92f),
            ) { content() }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            val bar = placeable ?: return@layout
            val start = controller.selection?.start ?: return@layout
            // Read here, not in measure: the caret is only known once the text is placed.
            controller.probes.revision
            val caret = controller.probes.caretWindow(start) ?: return@layout
            val origin = controller.viewport.topLeft
            val at = barOrigin(
                caret.translate(-origin.x, -origin.y),
                bar.width.toFloat(), bar.height.toFloat(),
                width.toFloat(), height.toFloat(),
                gapPx, handlePx,
            )
            bar.place(at.x.roundToInt(), at.y.roundToInt())
        }
    }
}

/** The actions for a selection, shown beside it: highlight colours, note, copy and share. */
@Composable
internal fun SelectionBar(
    palette: BookPalette,
    onHighlight: (HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    // The EzFiles reader's highlight bar: a dark scrim pill, colour dots, then the text actions.
    val haptics = rememberHaptics()
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(EzColors.Scrim.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HighlightColor.entries.forEach { color ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color.swatch())
                    .clickable(role = Role.Button, onClickLabel = "Highlight in ${color.name}") {
                        haptics.longPress()
                        onHighlight(color)
                    },
            )
        }
        SelectionAction("Note", onNote)
        SelectionAction("Copy", onCopy)
        BarIconButton(EzIcons.Share, "Share selection", onShare, tint = EzColors.OnScrim)
        BarIconButton(EzIcons.Close, "Clear selection", onClose, tint = EzColors.OnScrim)
    }
}

/** A text action on the selection bar: a pill with its label in white. */
@Composable
private fun SelectionAction(label: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = EzColors.OnScrim,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
