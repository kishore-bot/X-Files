package com.xfiles.feature.reader

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzMotion
import com.xfiles.design.Haptics
import com.xfiles.design.LocalReducedMotion
import com.xfiles.documents.book.MarkRange
import com.xfiles.documents.book.TextMark
import com.xfiles.documents.structure.ContentBlock
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Turns pages for the controls and the tap zones. The paged view sets [turn] while it is on screen. */
internal class BookPageNav {
    /** Turns by [delta] pages: -1 back, +1 forward. Set by [PagedBook] and cleared when it leaves. */
    var turn: ((Int) -> Unit)? = null

    fun step(delta: Int) {
        turn?.invoke(delta)
    }
}

/**
 * The paged reader for one layout: pages slide side by side in a [HorizontalPager]. Keyed on the
 * layout, so a settings change starts from a fresh pagination (the previous one stays in the cache).
 * Cutting pages runs in one effect that is restarted, and so cancels any stale work, whenever the
 * page or the jump target changes.
 */
@Composable
internal fun PagedBook(
    layout: BookLayout,
    palette: BookPalette,
    typography: BookTypography,
    marks: Map<Int, List<MarkRange<TextMark>>>,
    actions: BookBlockActions,
    marginDp: Int,
    initialBlock: Int,
    jumpTo: Int?,
    onJumpHandled: () -> Unit,
    onBlock: (Int) -> Unit,
    nav: BookPageNav,
    haptics: Haptics,
    measure: (ContentBlock) -> Int,
) {
    val index = layout.index
    val openingBlock = remember(layout) { initialBlock }
    var opening by remember(layout) { mutableStateOf(true) }
    val targetBlock = jumpTo ?: openingBlock.takeIf { opening }
    val pagerState = rememberPagerState(initialPage = index.pageOf(openingBlock)) {
        layout.revision.let { index.pageCount + if (index.isComplete) 0 else 1 }
    }
    RegisterViewerScroll(pagerState) { PagerScroll(pagerState) }
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current

    DisposableEffect(pagerState, reduced) {
        nav.turn = { delta ->
            val target = pagerState.currentPage + delta
            if (target >= 0 && target < pagerState.pageCount) {
                haptics.segment()
                scope.launch { pagerState.turnTo(target, reduced) }
            }
        }
        onDispose { nav.turn = null }
    }

    // Turning a page by hand ends a selection. A page turned by a drag held at an edge keeps it.
    LaunchedEffect(pagerState.currentPage) {
        if (!actions.selection.dragging) actions.selection.clear()
    }
    EdgeTurns(pagerState, actions.selection, haptics)

    // Cut pages while the reader is near the end of those cut, or the jump target is not yet on one.
    // Yields between pages so frames keep their deadline; restarts (cancelling the rest) on a new page or jump.
    LaunchedEffect(layout, targetBlock, pagerState.currentPage) {
        while (true) {
            val needJump = targetBlock != null && !index.covers(targetBlock)
            val needAhead = !index.isComplete && index.pageCount <= pagerState.currentPage + AHEAD
            if (!needJump && !needAhead) break
            layout.cutPages(pages = 1, measure = measure)
            yield()
        }
        if (targetBlock != null && index.covers(targetBlock)) {
            val targetPage = index.pageOf(targetBlock)
            if (pagerState.currentPage != targetPage) pagerState.scrollToPage(targetPage)
            opening = false
            if (jumpTo != null) onJumpHandled()
        }
    }
    // The first block becomes the saved position once the initial page or requested jump has landed.
    LaunchedEffect(pagerState.currentPage, layout.revision, targetBlock) {
        if (targetBlock == null && pagerState.currentPage < index.pageCount) onBlock(index.pageStart(pagerState.currentPage))
    }

    HorizontalPager(
        pagerState,
        Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
    ) { page ->
        // The page leaving or arriving is a little fainter and smaller, so the slide has depth.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val away = if (reduced) 0f
                    else abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
                alpha = 1f - PAGE_FADE * away
                scaleX = 1f - PAGE_SHRINK * away
                scaleY = 1f - PAGE_SHRINK * away
            },
        ) {
            if (page >= index.pageCount) {
                Box(Modifier.fillMaxSize())
            } else {
                BookPage(
                    layout = layout,
                    page = page,
                    palette = palette,
                    typography = typography,
                    marks = marks,
                    actions = actions,
                    marginDp = marginDp,
                )
            }
        }
    }
}

/**
 * A drag held in the left or right edge zone for EDGE_DWELL_NANOS turns the page, and the selection
 * carries on onto it. Leaving the zone re-arms the dwell, so one hold turns one page.
 */
@Composable
private fun EdgeTurns(pagerState: PagerState, selection: BookSelectionController, haptics: Haptics) {
    val zonePx = with(LocalDensity.current) { EdgeZone.toPx() }
    val dragging = selection.dragging
    val reduced = LocalReducedMotion.current
    LaunchedEffect(dragging, reduced) {
        if (!dragging) return@LaunchedEffect
        var side = 0
        var since = 0L
        var armed = false
        frameLoop { now, _ ->
            val edge = selection.horizontalEdge(zonePx)
            if (edge != side) {
                side = edge
                since = now
                armed = true
            }
            if (side != 0 && armed && now - since >= EDGE_DWELL_NANOS) {
                armed = false
                val target = pagerState.currentPage + side
                if (target in 0 until pagerState.pageCount) {
                    haptics.segment()
                    pagerState.turnTo(target, reduced)
                    selection.refresh()
                }
            }
        }
    }
}

@Composable
private fun BookPage(
    layout: BookLayout,
    page: Int,
    palette: BookPalette,
    typography: BookTypography,
    marks: Map<Int, List<MarkRange<TextMark>>>,
    actions: BookBlockActions,
    marginDp: Int,
) {
    val index = layout.index
    val start = index.pageStart(page)
    val end = index.pageEnd(page)
    val pad = Modifier.fillMaxSize().padding(start = marginDp.dp, end = marginDp.dp, top = BookInsetTop, bottom = BookInsetBottom)
    CompositionLocalProvider(LocalContentColor provides palette.text) {
        if (index.isOversized(page)) {
            // One block taller than a page: it gets the page to itself and scrolls.
            Column(pad.verticalScroll(rememberScrollState())) {
                for (i in start until end) BookBlockContent(layout, i, palette, typography, marks, actions)
            }
        } else {
            Column(pad.clipToBounds(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in start until end) BookBlockContent(layout, i, palette, typography, marks, actions)
            }
        }
    }
}

@Composable
private fun BookBlockContent(
    layout: BookLayout,
    index: Int,
    palette: BookPalette,
    typography: BookTypography,
    marks: Map<Int, List<MarkRange<TextMark>>>,
    actions: BookBlockActions,
) {
    BookBlockView(
        index = index,
        block = layout.blocks[index],
        typography = typography,
        palette = palette,
        marks = marksOf(marks, index),
        actions = actions,
    )
}

private suspend fun PagerState.turnTo(target: Int, reduced: Boolean) {
    if (reduced) scrollToPage(target)
    else animateScrollToPage(target, animationSpec = EzMotion.defaultSpatial())
}

private const val AHEAD = 2

/** How long a drag must be held in a side edge zone before the page turns. */
private const val EDGE_DWELL_NANOS = 500_000_000L

/** How much a page fades and shrinks as it slides away. */
private const val PAGE_FADE = 0.25f
private const val PAGE_SHRINK = 0.03f

/** One shared empty list, so blocks without marks keep the same argument across recompositions. */
private val NO_MARKS: List<MarkRange<TextMark>> = emptyList()

internal fun marksOf(marks: Map<Int, List<MarkRange<TextMark>>>, block: Int): List<MarkRange<TextMark>> =
    marks[block] ?: NO_MARKS
