package com.xfiles.feature.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.BarIconButton
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.PrimaryGradient
import com.xfiles.design.glass
import com.xfiles.design.glassBar
import com.xfiles.design.raised
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.SlideShape
import com.xfiles.documents.structure.StructuredDocument
import kotlin.math.absoluteValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

/**
 * The presentation viewer, matching the EzFiles layout: a swipeable stage of slides as cards (their
 * notes below), a thumbnail strip, glass controls with the page counter and a Present pill, and an
 * immersive presenter with each slide's own transition. Links, media and pictures on slides act
 * through [SlidePresentationViewModel].
 */
@Composable
internal fun PresentationPreview(document: StructuredDocument, slideEditor: SlideEditor? = null, onEdited: () -> Unit = {}) {
    val haptics = rememberHaptics()
    val uriHandler = LocalUriHandler.current
    val vm: SlidePresentationViewModel = hiltViewModel()
    val ui by vm.state.collectAsStateWithLifecycle()
    val slides = remember(document.blocks) { groupSlides(document.blocks) }
    val scope = rememberCoroutineScope()
    // The deck position is shared by the viewer and the presenter, so either can hand over to the other.
    var current by rememberSaveable { mutableIntStateOf(0) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var presenting by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    val position = current.coerceIn(0, slides.lastIndex.coerceAtLeast(0))
    val pager = rememberPagerState(initialPage = position) { slides.size }
    RegisterViewerScroll(pager) { PagerScroll(pager) }

    LaunchedEffect(document) { vm.bind(document) }
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is SlideEffect.GoTo -> {
                    current = effect.index
                    step = 0
                }
                is SlideEffect.Open -> try {
                    uriHandler.openUri(effect.url)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    vm.onEvent(SlideEvent.DismissMessage)
                }
            }
        }
    }
    // Swiping the stage sets the position; a position set elsewhere (a link, the presenter) moves the stage.
    LaunchedEffect(pager.settledPage) {
        if (!presenting && pager.settledPage != current) {
            current = pager.settledPage
            step = 0
        }
    }
    LaunchedEffect(position, presenting) {
        if (!presenting && pager.currentPage != position && !pager.isScrollInProgress) pager.animateScrollToPage(position)
    }

    val slide = slides.getOrNull(position)
    if (slide == null) {
        Text("This presentation has no readable slides.")
        return
    }

    fun actionsFor(index: Int): SlideActions = SlideActions(
        onLink = { link ->
            haptics.tick()
            vm.onEvent(SlideEvent.ActivateLink(link, index, slides.size))
        },
        onMedia = { shape ->
            haptics.confirm()
            vm.onEvent(SlideEvent.PlayMedia(shape))
        },
        onPicture = { path ->
            haptics.tick()
            vm.onEvent(SlideEvent.ViewPicture(path))
        },
    )

    if (presenting) {
        Presenter(
            slides = slides,
            index = position,
            step = step,
            onMove = { index, nextStep -> current = index; step = nextStep },
            actionsFor = ::actionsFor,
            onClose = { presenting = false },
        )
    }
    if (sorting && slideEditor != null) {
        SlideSorter(
            slides = slides,
            editor = slideEditor,
            onDismiss = { sorting = false },
            onEdited = { sorting = false; current = 0; onEdited() },
        )
    }
    ui.pendingUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { vm.onEvent(SlideEvent.DismissWebLink) },
            title = { Text("Open this link?") },
            text = { Text(url, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { haptics.confirm(); vm.onEvent(SlideEvent.ConfirmWebLink) }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { vm.onEvent(SlideEvent.DismissWebLink) }) { Text("Cancel") } },
        )
    }
    ui.media?.let { media -> SlideMediaPlayer(media) { vm.onEvent(SlideEvent.CloseMedia) } }
    ui.picture?.let { picture -> SlidePictureViewer(picture.bytes) { vm.onEvent(SlideEvent.ClosePicture) } }

    Column(Modifier.fillMaxSize().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val pageHeight = maxHeight
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 12.dp,
                beyondViewportPageCount = 1,
                key = { it },
            ) { page ->
                val item = slides[page]
                Column(
                    Modifier
                        .fillMaxSize()
                        .deckTransform(pager, page)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = pageHeight).padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    ) {
                        SlideFrame(item, Modifier.widthIn(max = 820.dp).fillMaxWidth(), actionsFor(page))
                        if (item.notes.isNotEmpty()) NotesPanel(item.notes)
                    }
                }
            }
        }
        Thumbnails(slides, position) { target -> scope.launch { pager.animateScrollToPage(target) } }
        if (document.truncated) Text("Showing the available slides within the preview limit.", color = EzColors.TextSecondary)
        SlideControls(
            current = position,
            count = slides.size,
            onPrevious = { scope.launch { pager.animateScrollToPage(position - 1) } },
            onNext = { scope.launch { pager.animateScrollToPage(position + 1) } },
            onPresent = { haptics.confirm(); presenting = true },
            onArrange = if (slideEditor != null) ({ haptics.tick(); sorting = true }) else null,
        )
    }
}

/**
 * Slides away like cards in a deck while swiping: the outgoing slide shrinks,
 * tilts and dims, the incoming one rises to full size. Runs in the graphics
 * layer only, so scrolling never recomposes the slide.
 */
private fun Modifier.deckTransform(pager: PagerState, page: Int) = graphicsLayer {
    val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
    val distance = offset.absoluteValue
    val scale = lerp(1f, 0.86f, distance)
    scaleX = scale
    scaleY = scale
    alpha = lerp(1f, 0.55f, distance)
    rotationY = offset * 18f
    cameraDistance = 12f * density
}

/** A slide as a raised card, or flat in the presenter; its linked text, media and pictures use [actions]. */
@Composable
private fun SlideFrame(
    slide: PreviewSlide,
    modifier: Modifier = Modifier,
    actions: SlideActions? = null,
    step: Int = Int.MAX_VALUE,
    animate: Boolean = false,
    elevated: Boolean = true,
) {
    val shape = RoundedCornerShape(if (elevated) 14.dp else 0.dp)
    Box(modifier.then(if (elevated) Modifier.raised(shape, 12.dp) else Modifier).clip(shape)) {
        SlideCanvas(slide.canvas, slide.shapes, Modifier.fillMaxWidth(), step = step, animate = animate, actions = actions)
    }
}

@Composable
private fun NotesPanel(notes: List<ContentBlock>) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), 0.55f).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Speaker notes", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = EzColors.Accent)
        notes.forEach { DocumentBlock(it) }
    }
}

/** Miniatures of every slide in a row; the current one is outlined in red. Tapping one jumps to it. */
@Composable
private fun Thumbnails(slides: List<PreviewSlide>, current: Int, onSelect: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == current }) list.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(slides, key = { _, slide -> slide.number }) { index, slide ->
            val selected = index == current
            val shape = RoundedCornerShape(8.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(92.dp)
                        .clip(shape)
                        .border(if (selected) 2.dp else 1.dp, if (selected) EzColors.Primary else EzColors.PageOutline, shape)
                        .clickable(role = Role.Tab) { onSelect(index) },
                ) {
                    // Miniatures are inert: taps select the slide, media plays from the full stage.
                    SlideCanvas(slide.canvas, slide.shapes, Modifier.fillMaxWidth())
                }
                Text(
                    "${slide.number}",
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) EzColors.Primary else EzColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Glass bar: previous, counter and next on the left; Arrange and the red Present pill on the right. */
@Composable
private fun SlideControls(
    current: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPresent: () -> Unit,
    onArrange: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).glassBar().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.glass(EzShapes.Pill, 0.6f), verticalAlignment = Alignment.CenterVertically) {
            BarIconButton(EzIcons.ChevronLeft, "Previous slide", onPrevious, enabled = current > 0)
            Text(
                if (count == 0) "-" else "${current + 1} / $count",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = EzColors.Text,
            )
            BarIconButton(EzIcons.ChevronRight, "Next slide", onNext, enabled = current < count - 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onArrange != null) BarIconButton(EzIcons.Grid, "Arrange slides", onArrange)
            Row(
                Modifier
                    .clip(EzShapes.Pill)
                    .background(PrimaryGradient)
                    .clickable(role = Role.Button, enabled = count > 0, onClick = onPresent)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(EzIcons.Play, null, tint = EzColors.OnPrimary, modifier = Modifier.size(18.dp))
                Text("Present", color = EzColors.OnPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

/** Where the presenter is: a slide and how many of its click builds have played. */
private data class Position(val index: Int, val step: Int)

/**
 * Full-screen presenter, landscape and immersive. A tap on the right or a swipe left plays the next
 * build, then the next slide with its transition; a tap on the left or a swipe right goes back. Taps
 * that land on a link, media badge or picture act on it instead of turning the page.
 */
@Composable
private fun Presenter(
    slides: List<PreviewSlide>,
    index: Int,
    step: Int,
    onMove: (Int, Int) -> Unit,
    actionsFor: (Int) -> SlideActions,
    onClose: () -> Unit,
) {
    val haptics = rememberHaptics()
    val position = Position(index.coerceIn(0, slides.lastIndex), step)
    val slide = slides[position.index]

    fun next() {
        haptics.tick()
        when {
            position.step < slide.lastStep -> onMove(position.index, position.step + 1)
            position.index < slides.lastIndex -> onMove(position.index + 1, 0)
        }
    }

    fun previous() {
        haptics.tick()
        when {
            position.step > 0 -> onMove(position.index, position.step - 1)
            position.index > 0 -> onMove(position.index - 1, slides[position.index - 1].lastStep)
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
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
                        Key.DirectionRight, Key.PageDown, Key.Spacebar, Key.VolumeDown -> next()
                        Key.DirectionLeft, Key.PageUp, Key.VolumeUp -> previous()
                        Key.Escape -> onClose()
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .pointerInput(slides.size) {
                    // A tap counts only if nothing in it consumed the release (a link, badge or picture did).
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation()
                        if (up != null && !up.isConsumed && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                            if (up.position.x > size.width / 3f) next() else previous()
                        }
                    }
                }
                .pointerInput(slides.size) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = { if (drag < -60f) next() else if (drag > 60f) previous() },
                    ) { change, amount ->
                        change.consume()
                        drag += amount
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = position,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
                contentKey = { it.index },
                transitionSpec = { slideTransition(slides) },
                label = "slide",
            ) { shown ->
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val shownSlide = slides[shown.index.coerceIn(0, slides.lastIndex)]
                    // Letterbox: the widest slide that still fits the screen's height.
                    val aspect = shownSlide.canvas.let { it.width / it.height }.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
                    val width = minOf(maxWidth, maxHeight * aspect)
                    // The outgoing slide keeps its builds; only the current one advances.
                    SlideFrame(
                        shownSlide,
                        Modifier.width(width),
                        actions = actionsFor(shown.index),
                        step = if (shown.index == position.index) position.step else Int.MAX_VALUE,
                        animate = true,
                        elevated = false,
                    )
                }
            }
            PresenterChrome(position.index, slides.size, onClose)
        }
    }
}

@Composable
private fun BoxScope.PresenterChrome(index: Int, count: Int, onClose: () -> Unit) {
    Row(
        Modifier.align(Alignment.TopEnd).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "${index + 1} / $count",
            color = EzColors.OnScrim.copy(alpha = 0.85f),
            fontSize = 13.sp,
            modifier = Modifier.clip(EzShapes.Pill).background(EzColors.Scrim).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(EzColors.Scrim).clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(EzIcons.Close, "Exit presentation", tint = EzColors.OnScrim)
        }
    }
    // Progress through the deck.
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth((index + 1f) / count.coerceAtLeast(1)).height(3.dp).background(PrimaryGradient))
    }
}

/**
 * The slide's own transition (`p:transition`), with PowerPoint's direction:
 * going back plays it mirrored. Slides without one get a short fade.
 */
private fun AnimatedContentTransitionScope<Position>.slideTransition(slides: List<PreviewSlide>): ContentTransform {
    val forward = targetState.index >= initialState.index
    val target = slides.getOrNull(if (forward) targetState.index else initialState.index)
    val dir = if (forward) 1 else -1
    val time = 520
    val ease = tween<Float>(time, easing = FastOutSlowInEasing)
    fun <T> t() = tween<T>(time, easing = FastOutSlowInEasing)
    val fade = fadeIn(ease) togetherWith fadeOut(ease)
    return when (target?.canvas?.transition) {
        null -> fadeIn(tween(300)) togetherWith fadeOut(tween(300))
        "cut" -> EnterTransition.None togetherWith ExitTransition.None
        "fade", "dissolve", "morph", "random", "randomBar" -> fade
        "push", "pan", "conveyor", "ferris", "flythrough", "gallery", "switch", "flip" ->
            slideInHorizontally(t()) { it * dir } togetherWith slideOutHorizontally(t()) { -it * dir }
        "cover", "comb", "doors", "window" ->
            slideInHorizontally(t()) { it * dir } togetherWith fadeOut(tween(time, delayMillis = time / 2))
        "pull", "uncover", "reveal", "curtains", "drape", "fallOver" ->
            (EnterTransition.None togetherWith slideOutHorizontally(t()) { -it * dir }).apply { targetContentZIndex = -1f }
        "wipe", "split", "strips", "blinds", "checker", "wedge", "wheel", "wheelReverse", "circle", "diamond", "plus", "shape", "honeycomb" ->
            expandHorizontally(t(), expandFrom = if (forward) Alignment.Start else Alignment.End) togetherWith
                fadeOut(tween(time, delayMillis = time / 2))
        "newsflash", "vortex", "ripple", "glitter", "shred", "prism", "doors3d" ->
            scaleIn(ease, initialScale = 0.4f) + fadeIn(ease) togetherWith scaleOut(ease, targetScale = 1.2f) + fadeOut(ease)
        "zoom", "warp", "flash" ->
            scaleIn(ease, initialScale = if (forward) 0.7f else 1.3f) + fadeIn(ease) togetherWith fadeOut(ease)
        "cube", "box", "rotate", "orbit" ->
            slideInHorizontally(t()) { it * dir } + scaleIn(ease, initialScale = 0.85f) togetherWith
                slideOutHorizontally(t()) { -it * dir } + scaleOut(ease, targetScale = 0.85f)
        "fly", "airplane", "origami", "fracture", "crush", "peelOff", "pageCurlSingle", "pageCurlDouble", "curl" ->
            slideInVertically(t()) { it / 3 } + fadeIn(ease) togetherWith fadeOut(ease)
        else -> fade
    }
}

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
    val dragState = androidx.compose.runtime.rememberUpdatedState(onDragMove)
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
    val number: Int,
    val title: String,
    val canvas: ContentBlock.Slide,
    val shapes: List<SlideShape>,
    val notes: List<ContentBlock>,
) {
    /** The last click step of this slide's entrance builds. */
    val lastStep: Int = shapes.maxOfOrNull { it.build?.step ?: 0 } ?: 0
}

private fun groupSlides(blocks: List<ContentBlock>): List<PreviewSlide> = buildList {
    var title = ""
    var canvas: ContentBlock.Slide? = null
    val shapes = mutableListOf<SlideShape>()
    val notes = mutableListOf<ContentBlock>()
    fun finish() {
        canvas?.let { add(PreviewSlide(size + 1, title, it, shapes.toList(), notes.toList())) }
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
