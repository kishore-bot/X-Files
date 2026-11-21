package com.xfiles.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.SlideShape
import com.xfiles.documents.structure.StructuredDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Slide tools backed by the engine. Each call writes a new copy (the original is never modified) and
 * returns the new slide count. Indices are 0-based positions in the file as currently loaded.
 * Implementations run their IO off the main thread and throw on failure.
 */
interface SlideEditor {
    /** Writes a copy whose slides follow [keep] (original indices, in the new order). */
    suspend fun reorder(keep: List<Int>): Int

    suspend fun delete(index: Int): Int

    suspend fun duplicate(index: Int): Int
}

@Composable
internal fun PresentationPreview(document: StructuredDocument, slideEditor: SlideEditor? = null, onEdited: () -> Unit = {}) {
    val slides = remember(document.blocks) { groupSlides(document.blocks) }
    var current by rememberSaveable { mutableIntStateOf(0) }
    var notesVisible by rememberSaveable { mutableStateOf(false) }
    var presenting by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    val position = current.coerceIn(0, slides.lastIndex.coerceAtLeast(0))
    val slide = slides.getOrNull(position)
    if (slide == null) {
        Text("This presentation has no readable slides.")
        return
    }
    if (presenting) {
        Slideshow(slides, position, onSlide = { current = it }, onExit = { presenting = false })
    }
    if (sorting && slideEditor != null) {
        SlideSorter(
            slides = slides,
            editor = slideEditor,
            onDismiss = { sorting = false },
            onEdited = { sorting = false; current = 0; onEdited() },
        )
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            SlideCanvas(slide.canvas, slide.shapes, Modifier.widthIn(max = 820.dp).fillMaxWidth())
        }
        if (notesVisible && slide.notes.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                slide.notes.forEach { DocumentBlock(it) }
            }
        }
        if (document.truncated) Text("Showing the available slides within the preview limit.", color = EzColors.TextSecondary)
        Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { current = position - 1 }, enabled = position > 0) {
                Icon(EzIcons.ChevronLeft, "Previous slide")
            }
            Column(Modifier.weight(1f)) {
                Text(slide.title.ifBlank { "Slide ${position + 1}" }, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                Text("${position + 1} / ${slides.size}", color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (slide.notes.isNotEmpty()) TextButton(onClick = { notesVisible = !notesVisible }) {
                Text(if (notesVisible) "Hide notes" else "Notes")
            }
            if (slideEditor != null) IconButton(onClick = { sorting = true }) { Icon(EzIcons.Grid, "Arrange slides") }
            IconButton(onClick = { presenting = true }) { Icon(EzIcons.Fullscreen, "Present") }
            IconButton(onClick = { current = position + 1 }, enabled = position < slides.lastIndex) {
                Icon(EzIcons.ChevronRight, "Next slide")
            }
        }
    }
}

/**
 * Full-screen presenting: black background, system bars hidden, screen kept on.
 *
 * - Tap or swipe the left and right thirds, arrow/page/space keys, or volume keys to move (volume
 *   down goes forward, volume up goes back; the key event is consumed while presenting).
 * - Tap the middle to show the chrome: slide counter, elapsed timer, notes toggle and stop.
 * - Long-press and drag anywhere to show a laser-pointer dot.
 * - Escape or the stop button leaves; leaving restores the system bars and screen timeout.
 */
@Composable
private fun Slideshow(slides: List<PreviewSlide>, start: Int, onSlide: (Int) -> Unit, onExit: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { slides.size }
    var chrome by remember { mutableStateOf(true) }
    var notesOn by remember { mutableStateOf(false) }
    var laser by remember { mutableStateOf<Offset?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val last = slides.lastIndex
    fun go(to: Int) {
        scope.launch { pager.animateScrollToPage(to.coerceIn(0, last)) }
    }
    LaunchedEffect(pager.currentPage) { onSlide(pager.currentPage) }
    LaunchedEffect(chrome) {
        if (chrome) {
            delay(2_000)
            chrome = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            elapsed++
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = true
            onDispose {
                view.keepScreenOn = false
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionRight, Key.PageDown, Key.Spacebar, Key.VolumeDown -> go(pager.currentPage + 1)
                        Key.DirectionLeft, Key.PageUp, Key.VolumeUp -> go(pager.currentPage - 1)
                        Key.Escape -> onExit()
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .pointerInput(slides.size) {
                    detectTapGestures { offset ->
                        val third = size.width / 3
                        when {
                            offset.x < third -> go(pager.currentPage - 1)
                            offset.x > 2 * third -> go(pager.currentPage + 1)
                            else -> chrome = true
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { laser = it },
                        onDrag = { change, _ ->
                            change.consume()
                            laser = change.position
                        },
                        onDragEnd = { laser = null },
                        onDragCancel = { laser = null },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { index ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SlideCanvas(slides[index].canvas, slides[index].shapes, Modifier.fillMaxWidth())
                }
            }
            laser?.let { point ->
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color(0x66FF2A2A), radius = 22.dp.toPx(), center = point)
                    drawCircle(Color(0xFFFF2A2A), radius = 6.dp.toPx(), center = point)
                }
            }
            AnimatedVisibility(chrome, Modifier.align(Alignment.TopEnd).padding(24.dp), enter = fadeIn(), exit = fadeOut()) {
                Text(
                    "${pager.currentPage + 1} / ${slides.size}  ·  ${formatElapsed(elapsed)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            val notes = slides.getOrNull(pager.currentPage)?.notes.orEmpty()
            if (notesOn && notes.isNotEmpty()) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 180.dp)
                        .background(Color(0xCC1B1B1F))
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text("Speaker notes", color = Color(0xFFB0B0B8), style = MaterialTheme.typography.labelMedium)
                    notes.forEach { DocumentBlock(it) }
                }
            }
            AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter).padding(24.dp), enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (notes.isNotEmpty()) {
                        TextButton(onClick = { notesOn = !notesOn }) {
                            Text(if (notesOn) "Hide notes" else "Notes", color = Color.White)
                        }
                    }
                    IconButton(onClick = onExit) { Icon(EzIcons.FullscreenExit, "Stop presenting", tint = Color.White) }
                }
            }
        }
    }
}

private fun formatElapsed(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * Drag-to-reorder grid of slide thumbnails. Dragging a tile (long-press, then move) moves it to the
 * slot nearest the finger; the new order is written as a copy with "Save order". Copy and Delete act on
 * the loaded file immediately and close the sorter.
 */
@Composable
private fun SlideSorter(slides: List<PreviewSlide>, editor: SlideEditor, onDismiss: () -> Unit, onEdited: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Stable keys are the original slide indices, so a drag moves one identity through the list.
    val order = remember(slides) { mutableStateListOf<Int>().apply { addAll(slides.indices) } }
    val grid = rememberLazyGridState()
    var draggingKey by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val changed = order.toList() != slides.indices.toList()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Arrange slides", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
            }
            Text("Long-press a slide and drag it to a new place.", color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(order, key = { it }) { original ->
                    SlideTile(
                        number = order.indexOf(original) + 1,
                        slide = slides[original],
                        dragging = draggingKey == original,
                        enabled = !busy,
                        onDragStart = { draggingKey = original },
                        onDragEnd = { draggingKey = null },
                        onDragMove = { startCenter, moved ->
                            val target = nearestKey(grid.layoutInfo, Offset(startCenter.x + moved.x, startCenter.y + moved.y))
                            if (target != null && target != original) {
                                val from = order.indexOf(original)
                                val to = order.indexOf(target)
                                if (from >= 0 && to >= 0 && from != to) order.add(to, order.removeAt(from))
                            }
                        },
                        layout = { grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == original } },
                        onDuplicate = {
                            runEdit(scope, { busy = it }, { error = it }, onEdited) { editor.duplicate(original) }
                        },
                        onDelete = {
                            runEdit(scope, { busy = it }, { error = it }, onEdited) { editor.delete(original) }
                        },
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (busy) Text("Saving copy", color = EzColors.TextSecondary)
                Button(
                    onClick = {
                        val keep = order.toList()
                        runEdit(scope, { busy = it }, { error = it }, onEdited) { editor.reorder(keep) }
                    },
                    enabled = changed && !busy,
                ) { Text("Save order") }
            }
        }
    }
}

@Composable
private fun SlideTile(
    number: Int,
    slide: PreviewSlide,
    dragging: Boolean,
    enabled: Boolean,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragMove: (startCenter: Offset, moved: Offset) -> Unit,
    layout: () -> androidx.compose.foundation.lazy.grid.LazyGridItemInfo?,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val dragState = rememberUpdatedState(onDragMove)
    Column(
        Modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // Start point and accumulated movement are local to this gesture.
                var startCenter = Offset.Zero
                var moved = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        val info = layout()
                        startCenter = info?.let { Offset(it.offset.x + it.size.width / 2f, it.offset.y + it.size.height / 2f) } ?: Offset.Zero
                        moved = Offset.Zero
                        onDragStart()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        moved += amount
                        dragState.value(startCenter, moved)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            }
            .background(if (dragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().background(Color(0xFF101014)).padding(4.dp)) {
            SlideCanvas(slide.canvas, slide.shapes, Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$number. ${slide.title.ifBlank { "Slide" }}", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onDuplicate, enabled = enabled) { Text("Copy") }
            TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
        }
    }
}

/** The grid slot whose centre is nearest [point]; keys are the original slide indices. */
private fun nearestKey(info: LazyGridLayoutInfo, point: Offset): Int? {
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    for (item in info.visibleItemsInfo) {
        val dx = item.offset.x + item.size.width / 2f - point.x
        val dy = item.offset.y + item.size.height / 2f - point.y
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = item.key as? Int
        }
    }
    return best
}

/** Runs one engine edit off the composition, reporting busy state and failures. */
private fun runEdit(
    scope: CoroutineScope,
    setBusy: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    onEdited: () -> Unit,
    edit: suspend () -> Int,
) {
    scope.launch {
        setBusy(true)
        setError(null)
        try {
            edit()
            onEdited()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            setError(failure.message ?: "The slide change could not be saved")
        } finally {
            setBusy(false)
        }
    }
}

private data class PreviewSlide(
    val title: String,
    val canvas: ContentBlock.Slide,
    val shapes: List<SlideShape>,
    val notes: List<ContentBlock>,
)

private fun groupSlides(blocks: List<ContentBlock>): List<PreviewSlide> = buildList {
    var title = ""
    var canvas: ContentBlock.Slide? = null
    val shapes = mutableListOf<SlideShape>()
    val notes = mutableListOf<ContentBlock>()
    fun finish() {
        canvas?.let { add(PreviewSlide(title, it, shapes.toList(), notes.toList())) }
        canvas = null
        shapes.clear()
        notes.clear()
    }
    for (block in blocks) {
        when (block) {
            is ContentBlock.Section -> {
                finish()
                title = block.title
            }
            is ContentBlock.Slide -> {
                if (canvas != null) finish()
                canvas = block
            }
            is ContentBlock.Shape -> shapes.add(block.shape)
            is ContentBlock.Quote -> notes.add(block)
            else -> Unit
        }
    }
    finish()
}
