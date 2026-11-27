package com.xfiles.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.xfiles.design.Haptics
import com.xfiles.documents.book.BookManifest
import com.xfiles.documents.book.MarkRange
import com.xfiles.documents.book.TextMark
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.feature.reader.domain.BookProgressUseCase

/** What the bars' buttons do. Each one opens a sheet or goes back; the screen decides how. */
internal class BookChromeActions(
    val onClose: () -> Unit,
    val onSearch: () -> Unit,
    val onContents: () -> Unit,
    val onBookmark: () -> Unit,
    val onSettings: () -> Unit,
    val onBack: () -> Unit,
    /** Opens Fily from the book's top bar; null hides the mic. */
    val onFily: (() -> Unit)? = null,
)

/**
 * The reader's surface: the book in the chosen mode, full screen, with the bars floating over its
 * top and bottom insets. With the bars away, the chapter sits above the text and the pages left
 * below it. The tap zones sit here: in paged mode the left and right thirds turn the page and the
 * centre toggles the bars; in scroll mode any tap that nothing else took toggles them.
 */
@Composable
internal fun BookReaderBody(
    blocks: List<ContentBlock>,
    manifest: BookManifest?,
    bookKey: String,
    title: String,
    bookmarked: Boolean,
    state: BookUiState,
    vm: BookViewModel,
    palette: BookPalette,
    typography: BookTypography,
    marks: Map<Int, List<MarkRange<TextMark>>>,
    actions: BookBlockActions,
    nav: BookPageNav,
    haptics: Haptics,
    current: Int,
    jumpTo: Int?,
    onJumpHandled: () -> Unit,
    onBlock: (Int) -> Unit,
    onJump: (Int) -> Unit,
    chrome: BookChromeActions,
) {
    val density = LocalDensity.current
    val progress = remember { BookProgressUseCase() }
    val paged = state.mode == BookReadingMode.Paged
    val pagedNow by rememberUpdatedState(paged)
    val zoneTap = remember(nav, vm) {
        { fraction: Float ->
            when {
                pagedNow && fraction < 1f / 3f -> nav.step(-1)
                pagedNow && fraction > 2f / 3f -> nav.step(1)
                else -> vm.onEvent(BookEvent.ToggleChrome)
            }
        }
    }
    val settings = state.settings
    val chapterStarts = remember(manifest, blocks.size) { progress.chapterStarts(manifest, blocks.size) }
    val ticks = remember(chapterStarts, blocks.size) { chapterStarts.map { progress.fraction(it, blocks.size) } }
    var scrollFraction by remember(bookKey, paged) { mutableStateOf<Float?>(null) }
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .onUnhandledTap { zoneTap(it) }
            .onGloballyPositioned { actions.selection.viewport = it.boundsInWindow() },
    ) {
        val marginPx = with(density) { settings.margin.dp.dp.roundToPx() }
        val topPx = with(density) { BookInsetTop.roundToPx() }
        val bottomPx = with(density) { BookInsetBottom.roundToPx() }
        val gapPx = with(density) { 10.dp.roundToPx() }
        val pageWidthPx = (constraints.maxWidth - 2 * marginPx).coerceAtLeast(1)
        val pageHeightPx = (constraints.maxHeight - topPx - bottomPx).coerceAtLeast(with(density) { 120.dp.roundToPx() })
        val layoutKey = BookLayoutKey(bookKey, settings.layout, pageWidthPx, pageHeightPx)
        // Paginated lazily; the cache keeps recent layouts, so returning to one is instant.
        val layout = remember(layoutKey) {
            vm.layouts.get(layoutKey) ?: BookLayout(blocks, pageHeightPx, gapPx, chapterStarts).also { vm.layouts.put(layoutKey, it) }
        }
        val measurer = remember(layout, typography, palette, pageWidthPx) {
            BookMeasurer(textMeasurer, density, typography, pageWidthPx, palette.onDark)
        }
        val measure: (ContentBlock) -> Int = { measurer.height(it) }

        if (paged) {
            key(layoutKey) {
                PagedBook(
                    layout = layout,
                    palette = palette,
                    typography = typography,
                    marks = marks,
                    actions = actions,
                    marginDp = settings.margin.dp,
                    jumpTo = jumpTo,
                    onJumpHandled = onJumpHandled,
                    onBlock = onBlock,
                    nav = nav,
                    haptics = haptics,
                    measure = measure,
                )
            }
        } else {
            BookScroll(
                blocks = blocks,
                settings = settings,
                palette = palette,
                typography = typography,
                marks = marks,
                actions = actions,
                initial = current,
                jumpTo = jumpTo,
                onJumpHandled = onJumpHandled,
                onBlock = onBlock,
                onProgress = { scrollFraction = it },
            )
        }

        // The page position is read here, so the footer follows pagination as it grows.
        val revision = layout.revision
        val chapter = remember(manifest, current, blocks.size) { progress.chapterBlocks(manifest, current, blocks.size) }
        val pageInChapter = if (paged && revision > 0) progress.pageInChapter(layout.index, chapter, current) else null
        val pageText = pageInChapter?.let { info ->
            info.total?.let { "Page ${info.page} of $it in chapter" } ?: "Page ${info.page} in chapter"
        }
        // "N pages left in chapter", as the EzFiles reader counts them; empty when the layout is not paged.
        val leftText = pageInChapter?.let { info ->
            val total = info.total
            when {
                total == null -> "Page ${info.page} in chapter"
                info.page >= total -> "Last page in chapter"
                total - info.page == 1 -> "1 page left in chapter"
                else -> "${total - info.page} pages left in chapter"
            }
        }.orEmpty()
        val fraction = if (paged && revision > 0) progress.pageFraction(layout.index, current)
            else scrollFraction ?: progress.fraction(current, blocks.size)
        val percent = (fraction * 100f).toInt()
        val chapterTitle = manifest?.let { it.chapterTitle(it.chapterIndexAt(current)) }?.takeIf { it.isNotBlank() } ?: title
        val faint = palette.text.copy(alpha = 0.55f)

        AnimatedVisibility(
            visible = state.chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            BookTopBar(
                title = title,
                bookmarked = bookmarked,
                onClose = chrome.onClose,
                onSearch = chrome.onSearch,
                onContents = chrome.onContents,
                onSettings = chrome.onSettings,
                onBookmark = chrome.onBookmark,
                onHide = { vm.onEvent(BookEvent.ToggleChrome) },
                onFily = chrome.onFily,
            )
        }
        if (!state.chromeVisible) BookChapterLine(chapterTitle, faint, Modifier.align(Alignment.TopCenter))
        AnimatedVisibility(
            visible = state.chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            BookBottomBar(
                chapterTitle = chapterTitle,
                fraction = fraction,
                ticks = ticks,
                footer = bookFooter(pageText, percent),
                onScrub = { fraction ->
                    haptics.segment()
                    onJump(progress.blockAt(fraction, blocks.size))
                },
                onPrevious = if (paged) ({ nav.step(-1) }) else null,
                onNext = if (paged) ({ nav.step(1) }) else null,
            )
        }
        if (!state.chromeVisible) {
            BookPageFooter(leftText, "$percent%", faint, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        }
        AnimatedVisibility(
            visible = state.backStack.isNotEmpty(),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = BookInsetTop),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            BookBackPill(onBack = chrome.onBack)
        }
        SelectionLayer(actions.selection, blocks, palette, vm, haptics)
    }
}
