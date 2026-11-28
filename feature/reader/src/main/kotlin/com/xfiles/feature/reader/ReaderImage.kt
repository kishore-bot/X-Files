package com.xfiles.feature.reader

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import com.xfiles.core.documents.PdfMatchBox
import com.xfiles.documents.PdfBox
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Past this zoom the picture's visible tiles are decoded at full resolution (only when [regions] is given). */
private const val SHARP_ZOOM = 1.3f

/** How far a one-finger drag must travel vertically, at fit, before it turns the page. */
private const val SWIPE_PAGE_PX = 160f

/**
 * A picture that zooms and pans, rotated in quarter turns. The base bitmap is drawn at fit; past [SHARP_ZOOM] the
 * tile layer ([SharpTiles]) draws full-resolution tiles over it in the same transform. [regions] enables that
 * layer; PDF pages pass null and keep the plain viewer. [animated] replaces the still image with a playing
 * animation ([animating] says whether it plays now). [onZoomChanged] reports zoom past fit for the gallery pager.
 *
 * A PDF page passes [text] to select words (long press, drag, handles) and [marks] to show its highlights.
 * Selection points are page fractions, so they hold under zoom, turns and the two-page spread.
 */
@Composable
internal fun ReaderImage(
    bitmap: Bitmap,
    description: String,
    rotation: Int,
    highlights: List<PdfMatchBox> = emptyList(),
    modifier: Modifier = Modifier.fillMaxSize(),
    /** Reports whether the picture is zoomed in past fit; the gallery pager uses it to hold paging. */
    onZoomChanged: (Boolean) -> Unit = {},
    /**
     * A vertical swipe while the page is at fit (not zoomed) turns the page: +1 for the next page, -1 for the previous.
     * Zoomed in, the same drag pans the page instead.
     */
    onSwipePage: (Int) -> Unit = {},
    regions: PictureRegions? = null,
    animated: AnimatedImageDrawable? = null,
    animating: Boolean = false,
    text: PdfTextSource? = null,
    marks: List<PdfBox> = emptyList(),
    /** Filter for the page's pixels (the page style's dark or sepia tone); null draws the page as it is. */
    colorFilter: ColorFilter? = null,
    pictureKey: String? = null,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val progress = LocalViewerProgress.current
    val settings = remember(progress?.store, pictureKey, rotation) {
        pictureKey?.let { progress?.store?.read(it, "image-transform:$rotation") }?.split(':').orEmpty()
    }
    fun component(index: Int, fallback: Float) = settings.getOrNull(index)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback
    var zoom by remember(bitmap, rotation) { mutableFloatStateOf(component(0, 1f).coerceIn(1f, 5f)) }
    var offset by remember(bitmap, rotation) { mutableStateOf(Offset(component(1, 0f), component(2, 0f))) }
    if (pictureKey != null && progress != null) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalViewerProgress provides remember(progress.store, pictureKey) { ViewerProgress(progress.store, pictureKey) },
        ) {
            SaveViewerProgress("image-transform:$rotation") { "$zoom:${offset.x}:${offset.y}" }
        }
    }

    val turned = rotation % 180 != 0
    val width = if (turned) bitmap.height else bitmap.width
    val height = if (turned) bitmap.width else bitmap.height
    val renderScale = fitScale(viewport, width, height)
    val originalScale = fitScale(viewport, bitmap.width, bitmap.height)
    val rotatedScale = if (originalScale > 0f) renderScale / originalScale else 1f

    val haptics = LocalHapticFeedback.current
    val selection = remember(bitmap, text?.page) { PdfTextSelection(haptics) }
    val currentText by rememberUpdatedState(text)
    val reportZoom by rememberUpdatedState(onZoomChanged)
    val swipePage by rememberUpdatedState(onSwipePage)
    val scope = rememberCoroutineScope()
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val handleColor = MaterialTheme.colorScheme.primary

    /** The page-to-window mapping as drawn right now: fit to the viewport, turned, zoomed, then panned. */
    fun pageTransform() = PdfPageTransform(
        viewportWidth = viewport.width.toFloat(),
        viewportHeight = viewport.height.toFloat(),
        pageWidth = bitmap.width.toFloat(),
        pageHeight = bitmap.height.toFloat(),
        rotation = rotation,
        zoom = zoom,
        offset = offset,
    )

    fun clamp(pan: Offset, scale: Float): Offset {
        if (!pan.isSpecified) return Offset.Zero
        val horizontal = ((width * renderScale * scale - viewport.width) / 2).coerceAtLeast(0f)
        val vertical = ((height * renderScale * scale - viewport.height) / 2).coerceAtLeast(0f)
        val x = if (pan.x.isFinite()) pan.x.coerceIn(-horizontal, horizontal) else 0f
        val y = if (pan.y.isFinite()) pan.y.coerceIn(-vertical, vertical) else 0f
        return Offset(x, y)
    }

    androidx.compose.runtime.LaunchedEffect(viewport, rotation) {
        if (viewport != IntSize.Zero) {
            offset = clamp(offset, zoom)
            reportZoom(zoom > 1.001f)
        }
    }
    Box(
        modifier.clipToBounds().onSizeChanged { viewport = it }
            .pointerInput(bitmap, rotation, viewport) {
                // One gesture loop for pinch, pan and page swipes. A one-finger drag at fit is a swipe (page turn),
                // so it no longer gets swallowed as a zero-size pan; zoomed in, the same drag pans the page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var swipeX = 0f
                    var swipeY = 0f
                    var swiped = false
                    var usedMultipleFingers = false
                    var panning = false
                    var pendingPan = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        usedMultipleFingers = usedMultipleFingers || pressed > 1
                        // A text selection owns the drag: the page holds still while a word or a handle is moved.
                        if (selection.dragging) continue
                        // An up event has no continuing pointers, so its centroid is unspecified.
                        // Leave down/up events and stationary taps to the tap recognizer.
                        if (event.changes.none { it.previousPressed && it.pressed }) continue
                        val zoomChange = event.calculateZoom()
                        var pan = event.calculatePan()
                        if (pan == Offset.Zero && zoomChange == 1f) continue
                        if (pressed > 1 || zoom > 1.001f) {
                            if (pressed == 1 && !panning && !usedMultipleFingers) {
                                pendingPan += pan
                                if (pendingPan.getDistance() <= viewConfiguration.touchSlop) continue
                                pan = pendingPan
                            }
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (!centroid.isSpecified || !centroid.x.isFinite() || !centroid.y.isFinite() || !zoomChange.isFinite()) continue
                            panning = true
                            val previous = zoom
                            val updated = (previous * zoomChange).coerceIn(1f, 5f)
                            val center = Offset(viewport.width / 2f, viewport.height / 2f)
                            val focus = centroid - center
                            val nextOffset = focus + (offset - focus) * (updated / previous) + pan
                            zoom = updated
                            offset = clamp(nextOffset, updated)
                            if ((previous > 1.001f) != (updated > 1.001f)) reportZoom(updated > 1.001f)
                            event.changes.forEach { it.consume() }
                        } else if (pressed == 1 && !swiped && !usedMultipleFingers) {
                            swipeX += pan.x
                            swipeY += pan.y
                            if (abs(swipeY) > SWIPE_PAGE_PX && abs(swipeY) > abs(swipeX) * 1.5f) {
                                swiped = true
                                swipePage(if (swipeY < 0f) 1 else -1)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(bitmap, rotation, viewport) {
                detectTapGestures(
                    onTap = { if (selection.range.value != null) selection.clear() },
                    onDoubleTap = { tap ->
                        val zoomIn = zoom <= 1.001f
                        val center = Offset(viewport.width / 2f, viewport.height / 2f)
                        val nextZoom = if (zoomIn) 2.5f else 1f
                        offset = if (zoomIn) clamp((center - tap) * (nextZoom - 1f), nextZoom) else Offset.Zero
                        zoom = nextZoom
                        reportZoom(zoomIn)
                    },
                )
            }
            .then(
                if (text == null) Modifier else Modifier.pointerInput(bitmap, rotation, text.page) {
                    var active = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            active = true
                            val page = pageTransform().windowToPage(at)
                            if (selection.geometry != null) {
                                selection.begin(page.x, page.y)
                            } else {
                                // The page's text is read off the main thread the first time; the press then selects.
                                scope.launch {
                                    val loaded = currentText?.load() ?: return@launch
                                    selection.geometry = loaded
                                    if (active) selection.begin(page.x, page.y)
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val page = pageTransform().windowToPage(change.position)
                            selection.extendTo(page.x, page.y)
                        },
                        onDragEnd = {
                            active = false
                            selection.endDrag()
                        },
                        onDragCancel = {
                            active = false
                            selection.endDrag()
                        },
                    )
                },
            ),
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                rotationZ = rotation.toFloat()
                scaleX = rotatedScale * zoom
                scaleY = rotatedScale * zoom
                translationX = offset.x
                translationY = offset.y
            },
        ) {
            if (animated != null) {
                AnimatedPicture(animated, animating, Modifier.fillMaxSize())
            } else {
                Image(image, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = colorFilter)
            }
            if (regions != null && animated == null && zoom > SHARP_ZOOM && viewport.width > 0) {
                SharpTiles(
                    regions = regions,
                    view = TileView(viewport, zoom, offset, rotation, renderScale, originalScale),
                    baseWidth = bitmap.width,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (highlights.isNotEmpty() || marks.isNotEmpty() || text != null) {
                // Drawn inside the turned layer, so boxes in page fractions follow the page under zoom and turns.
                Canvas(Modifier.fillMaxSize()) {
                    val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    val left = (size.width - width) / 2
                    val top = (size.height - height) / 2
                    for (box in marks) {
                        if (box.right <= box.left || box.bottom <= box.top) continue
                        drawRect(
                            Color(0x75FFC928),
                            topLeft = Offset(left + box.left * width, top + box.top * height),
                            size = Size((box.right - box.left) * width, (box.bottom - box.top) * height),
                        )
                    }
                    for (box in highlights) {
                        if (box.right <= box.left || box.bottom <= box.top) continue
                        drawRect(
                            Color(0xFFFFC928).copy(alpha = 0.42f),
                            topLeft = Offset(left + box.left * width, top + box.top * height),
                            size = Size((box.right - box.left) * width, (box.bottom - box.top) * height),
                        )
                    }
                    // Draw phase: the selection is read here, so moving it does not recompose the page.
                    selection.range.value?.boxes?.forEach { box ->
                        drawRect(
                            selectionColor,
                            topLeft = Offset(left + box.left * width, top + box.top * height),
                            size = Size((box.right - box.left) * width, (box.bottom - box.top) * height),
                        )
                    }
                }
            }
        }
        val source = text
        if (source != null) {
            PdfSelectionOverlay(
                selection = selection,
                source = source,
                transform = ::pageTransform,
                color = handleColor,
                onClear = { selection.clear() },
            )
        }
    }
}

private fun fitScale(container: IntSize, width: Int, height: Int): Float =
    minOf(container.width.toFloat() / width, container.height.toFloat() / height)
