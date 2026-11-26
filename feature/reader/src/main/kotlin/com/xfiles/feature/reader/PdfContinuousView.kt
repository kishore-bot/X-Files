package com.xfiles.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfMatchBox
import com.xfiles.documents.PdfBox
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val PAPER_ASPECT = 1f / 1.414f

/**
 * The PDF as one continuous column of pages (EzFiles' single-page view), or two pages per row when [twoUp].
 * Pages render as they come into view through [loadPage]. Scrolling reports the most visible page, and
 * [jump] carries explicit page navigation from controls and search.
 * Pinch zooms in steps and reports through [onZoom].
 */
@Composable
internal fun PdfContinuousView(
    pageCount: Int,
    currentPage: Int,
    jump: PdfPageJump?,
    turningPage: Boolean,
    twoUp: Boolean,
    zoom: Int,
    loadPage: suspend (Int) -> FilePreview.Pdf?,
    highlights: (Int) -> List<PdfMatchBox>,
    marks: (Int) -> List<PdfBox>,
    textSource: (Int) -> PdfTextSource,
    colorFilter: ColorFilter?,
    onPageSettled: (Int) -> Unit,
    onVisiblePage: (Int) -> Unit,
    onZoom: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val perRow = if (twoUp) 2 else 1
    val rows = (pageCount + perRow - 1) / perRow
    val progress = LocalViewerProgress.current
    val saved = remember(progress) { progress?.read("pdf-position")?.split(':')?.mapNotNull(String::toIntOrNull).orEmpty() }
    val anchor = saved.takeIf { it.size == 4 && it[0] == currentPage && it[3] == perRow }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (anchor?.get(1) ?: (currentPage / perRow)).coerceIn(0, (rows - 1).coerceAtLeast(0)),
        initialFirstVisibleItemScrollOffset = anchor?.get(2)?.coerceAtLeast(0) ?: 0,
    )
    RegisterViewerScroll(listState) { LazyScroll(listState) }
    val scrollX = rememberViewerScroll("pdf-x")
    val currentZoom by rememberUpdatedState(zoom)
    val reportPage by rememberUpdatedState(onPageSettled)
    val showPage by rememberUpdatedState(onVisiblePage)
    val selectedPage by rememberUpdatedState(currentPage)
    var pinch by remember { mutableFloatStateOf(1f) }
    var handledJump by rememberSaveable { mutableStateOf<Long?>(null) }
    var layoutColumns by rememberSaveable { mutableStateOf(perRow) }
    var movingToPage by remember { mutableStateOf(false) }
    var settledPosition by remember { mutableStateOf<String?>(null) }
    val requestedJump by rememberUpdatedState(jump)
    val loadingPage by rememberUpdatedState(turningPage)
    var viewportWidth by remember { mutableIntStateOf(0) }
    val spreadGap by rememberUpdatedState(with(LocalDensity.current) { 8.dp.roundToPx() })

    fun spreadWidth(): Int = (viewportWidth * (currentZoom / 100f)).roundToInt()

    fun secondPageStart(): Int = (spreadWidth() + spreadGap) / 2

    fun visiblePage(): Int? {
        val layout = listState.layoutInfo
        val row = layout.visibleItemsInfo.maxByOrNull { item ->
            minOf(item.offset + item.size, layout.viewportEndOffset) - maxOf(item.offset, layout.viewportStartOffset)
        }?.index ?: return null
        val first = (row * perRow).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val preferred = requestedJump?.page?.takeIf { movingToPage || loadingPage } ?: selectedPage
        if (perRow == 2 && first + 1 < pageCount && viewportWidth > 0 && currentZoom > 100) {
            val start = scrollX.value
            val end = start + viewportWidth
            val left = (minOf(end, secondPageStart() - spreadGap) - start).coerceAtLeast(0)
            val right = (minOf(end, spreadWidth()) - maxOf(start, secondPageStart())).coerceAtLeast(0)
            if (left != right) return if (right > left) first + 1 else first
        }
        return preferred.takeIf { it / perRow == row } ?: first
    }

    LaunchedEffect(jump, perRow) {
        val newJump = jump?.takeIf { it.id != handledJump }
        if (newJump == null && layoutColumns == perRow) return@LaunchedEffect
        val target = ((newJump?.page ?: selectedPage) / perRow).coerceIn(0, (rows - 1).coerceAtLeast(0))
        movingToPage = true
        try {
            listState.scrollToItem(target)
            if (newJump != null) {
                if (perRow == 2 && currentZoom > 100) {
                    snapshotFlow { viewportWidth > 0 && scrollX.maxValue != Int.MAX_VALUE }.first { it }
                    scrollX.scrollTo(if (newJump.page % 2 == 0) 0 else secondPageStart())
                }
                handledJump = newJump.id
            }
            layoutColumns = perRow
        } finally {
            movingToPage = false
        }
    }
    LaunchedEffect(listState, perRow, pageCount) {
        var reportedPage: Int? = null
        snapshotFlow {
            val page = visiblePage()
            val moving = listState.isScrollInProgress || scrollX.isScrollInProgress || movingToPage || layoutColumns != perRow ||
                (requestedJump?.id != null && requestedJump?.id != handledJump)
            val anchor = if (page != null && !moving) {
                "$page:${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset}:$perRow"
            } else null
            Triple(page, anchor, loadingPage)
        }.collect { (page, anchor, loading) ->
            if (page != null) {
                showPage(page)
                if (anchor == null) reportedPage = null
                if (anchor != null) {
                    settledPosition = anchor
                    if (!loading && page != reportedPage) {
                        reportedPage = page
                        if (page != selectedPage) reportPage(page)
                    }
                }
            }
        }
    }
    SaveViewerProgress("pdf-position") { settledPosition }

    BoxWithConstraints(modifier.fillMaxSize().onSizeChanged { viewportWidth = it.width }) {
        val contentWidth: Dp = maxWidth * (zoom / 100f)
        val rowWidth = if (zoom > 100) contentWidth else maxWidth
        val slotWidth = (rowWidth - 8.dp * (perRow - 1)) / perRow
        Box(
            Modifier
                .fillMaxSize()
                .then(if (zoom > 100) Modifier.horizontalScroll(scrollX) else Modifier)
                .pointerInput(Unit) {
                    // Two fingers pinch: the page follows the fingers, and the zoom commits on release.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pinching = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                pinching = true
                                val base = currentZoom.toFloat()
                                pinch = (pinch * event.calculateZoom()).coerceIn(PDF_MIN_ZOOM / base, PDF_MAX_ZOOM / base)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) {
                            onZoom(((currentZoom * pinch) / 5f).roundToInt() * 5)
                            pinch = 1f
                        }
                    }
                }
                .graphicsLayerScale(pinch),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(rowWidth).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(count = rows) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (index in row * perRow until minOf(pageCount, (row + 1) * perRow)) {
                            PdfPageSlot(
                                index = index,
                                width = slotWidth,
                                loadPage = loadPage,
                                highlights = highlights(index),
                                marks = marks(index),
                                source = textSource(index),
                                colorFilter = colorFilter,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Scales the content by the live pinch factor; a no-op at 1. */
private fun Modifier.graphicsLayerScale(scale: Float): Modifier =
    if (scale == 1f) this else graphicsLayer {
        scaleX = scale
        scaleY = scale
    }

/** One page: a paper card that renders while it is on screen, with find and mark boxes and text selection. */
@Composable
private fun PdfPageSlot(
    index: Int,
    width: Dp,
    loadPage: suspend (Int) -> FilePreview.Pdf?,
    highlights: List<PdfMatchBox>,
    marks: List<PdfBox>,
    source: PdfTextSource,
    colorFilter: ColorFilter?,
) {
    val loaded by produceState<FilePreview.Pdf?>(null, index) { value = loadPage(index) }
    val bitmap: Bitmap? = loaded?.bitmap
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: PAPER_ASPECT
    val haptics = LocalHapticFeedback.current
    val selection = remember(index, bitmap) { PdfTextSelection(haptics) }
    val scope = rememberCoroutineScope()
    val currentSource by rememberUpdatedState(source)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val pageWidth = bitmap?.width?.toFloat() ?: 1f
    val pageHeight = bitmap?.height?.toFloat() ?: 1f
    val pageTransform: () -> PdfPageTransform = {
        PdfPageTransform(viewport.width.toFloat(), viewport.height.toFloat(), pageWidth, pageHeight, 0, 1f, Offset.Zero)
    }
    val paper = RoundedCornerShape(4.dp)
    val image = bitmap?.asImageBitmap()
    Box(
        Modifier
            .width(width)
            .aspectRatio(aspect)
            .shadow(6.dp, paper)
            .clip(paper)
            .background(Color.White)
            .onSizeChanged { viewport = it }
            .then(
                if (image == null) Modifier else Modifier
                    .pointerInput(index, bitmap) {
                        detectTapGestures(onTap = { if (selection.range.value != null) selection.clear() })
                    }
                    .pointerInput(index, bitmap) {
                        var active = false
                        detectDragGesturesAfterLongPress(
                            onDragStart = { at ->
                                active = true
                                val point = pageTransform().windowToPage(at)
                                if (selection.geometry != null) {
                                    selection.begin(point.x, point.y)
                                } else {
                                    scope.launch {
                                        val loaded = currentSource.load() ?: return@launch
                                        selection.geometry = loaded
                                        if (active) selection.begin(point.x, point.y)
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val point = pageTransform().windowToPage(change.position)
                                selection.extendTo(point.x, point.y)
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
        if (image != null) {
            Image(
                image,
                "Page ${index + 1}",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = colorFilter,
            )
            val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            Canvas(Modifier.fillMaxSize()) {
                for (box in marks) {
                    if (box.right <= box.left || box.bottom <= box.top) continue
                    drawRect(
                        Color(0x75FFC928),
                        topLeft = Offset(box.left * size.width, box.top * size.height),
                        size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                    )
                }
                for (box in highlights) {
                    if (box.right <= box.left || box.bottom <= box.top) continue
                    drawRect(
                        Color(0xFFFFC928).copy(alpha = 0.42f),
                        topLeft = Offset(box.left * size.width, box.top * size.height),
                        size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                    )
                }
                selection.range.value?.boxes?.forEach { box ->
                    drawRect(
                        selectionColor,
                        topLeft = Offset(box.left * size.width, box.top * size.height),
                        size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                    )
                }
            }
            PdfSelectionOverlay(
                selection = selection,
                source = source,
                transform = pageTransform,
                color = MaterialTheme.colorScheme.primary,
                onClear = { selection.clear() },
            )
        }
    }
}
