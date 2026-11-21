package com.xfiles.feature.reader

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import com.xfiles.documents.book.BookManifest
import com.xfiles.documents.book.MarkRange
import com.xfiles.documents.book.NotebookKind
import com.xfiles.documents.book.ReadingSettings
import com.xfiles.documents.book.TextMark
import com.xfiles.documents.book.bookKey
import com.xfiles.documents.book.selectionSpans
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.StructuredDocument
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * An e-book, read in pages or as one scroll. Pages are cut lazily: only as far as the reader
 * has reached, or the block being jumped to, so a long book is never laid out at once. Contents,
 * the "Aa" settings, highlights, notes, footnotes and the notebook are all sheets over the page.
 * A long press selects text: handles adjust it, and a bar below it highlights, notes, copies or shares it.
 */
@Composable
internal fun BookPreview(document: StructuredDocument) {
    val blocks = document.blocks
    if (blocks.isEmpty()) {
        Text("This book has no readable content.")
        return
    }
    val manifest = document.book
    val key = remember(document) { bookKey(document.info.name, document.info.byteSize, manifest) }
    val vm: BookViewModel = hiltViewModel()
    LaunchedEffect(key) { vm.open(key) }
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.key != key) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val settings = state.settings
    val palette = remember(settings.theme) { bookPalette(settings.theme) }
    val typography = remember(settings.layout, palette) { bookTypography(settings, palette) }
    val annotations = state.annotations
    // Keyed on the lists themselves: a page turn changes only the position, and must not rebuild these.
    val marks = remember(annotations.highlights, annotations.notes) { annotations.marksByBlock() }
    val notebook = remember(annotations.highlights, annotations.notes, annotations.bookmarks) { annotations.notebook() }
    val haptics = LocalHapticFeedback.current
    val sheet = state.sheet

    // The live selection and what hit-tests the text on screen. Replaced when the book changes.
    val probes = remember(key) { TextProbes() }
    val selectionState: MutableState<LiveSelection?> = remember(key) { mutableStateOf<LiveSelection?>(null) }
    val selection = remember(key, haptics) { BookSelectionController(probes, selectionState, haptics) }

    var paged by rememberSaveable { mutableStateOf(true) }
    // The block the reader is at; set by whichever view is showing, and saved as the position.
    var current by remember(key) { mutableIntStateOf(annotations.position) }
    val currentBlock by rememberUpdatedState(current)
    // A block to bring into view; cleared once it is shown.
    var jumpTo by remember(key) { mutableStateOf<Int?>(annotations.position.takeIf { it > 0 }) }
    val onBlock: (Int) -> Unit = { block ->
        current = block
        vm.savePosition(block)
    }
    val jump: (Int) -> Unit = { block ->
        vm.dismissSheet()
        jumpTo = block
    }

    val actions = remember(blocks, manifest, vm, selection) {
        BookBlockActions(
            onNoteLink = { href ->
                val id = href.removePrefix(BookManifest.NOTE_PREFIX)
                val text = manifest?.notes?.get(id) ?: "This note is not in the book's files."
                vm.showSheet(BookSheet.Footnote(label = "Note", text = text))
            },
            onInternalLink = { href ->
                // Only a link to a place in this book moves the reader, and system back returns from it.
                manifest?.blockOf(href)?.let { target ->
                    selection.clear()
                    vm.pushJump(currentBlock)
                    jump(target)
                }
            },
            onMark = { block, mark ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                vm.showSheet(
                    when (mark) {
                        is TextMark.Highlighted -> BookSheet.Marked(block, mark.id, null)
                        is TextMark.Noted -> BookSheet.Marked(block, null, mark.id)
                    },
                )
            },
            selection = selection,
        )
    }

    BackHandler(enabled = state.backStack.isNotEmpty()) {
        vm.popJump()?.let(jump)
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        BookTopBar(
            title = manifest?.title?.takeIf { it.isNotBlank() } ?: document.info.name,
            bookmarked = annotations.isBookmarked(current),
            paged = paged,
            tint = palette.text,
            onContents = { vm.showSheet(BookSheet.Contents) },
            onSettings = { vm.showSheet(BookSheet.Settings) },
            onNotebook = { vm.showSheet(BookSheet.Notebook) },
            onBookmark = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                vm.toggleBookmark(current, manifest?.let { chapterLabel(it, current) } ?: "Bookmark")
            },
            onToggleMode = {
                // The other view opens where this one is.
                selection.clear()
                jumpTo = current
                paged = !paged
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { selection.viewport = it.boundsInWindow() }) {
            if (paged) {
                BookPages(
                    blocks = blocks,
                    bookKey = key,
                    vm = vm,
                    settings = settings,
                    palette = palette,
                    typography = typography,
                    marks = marks,
                    actions = actions,
                    jumpTo = jumpTo,
                    onJumpHandled = { jumpTo = null },
                    onBlock = onBlock,
                )
            } else {
                BookScroll(
                    blocks = blocks,
                    settings = settings,
                    palette = palette,
                    typography = typography,
                    marks = marks,
                    actions = actions,
                    initial = annotations.position,
                    jumpTo = jumpTo,
                    onJumpHandled = { jumpTo = null },
                    onBlock = onBlock,
                )
            }
            SelectionLayer(selection, blocks, palette, vm)
        }
    }

    when (sheet) {
        BookSheet.None -> Unit
        BookSheet.Contents -> BookContentsSheet(manifest, onJump = jump, onDismiss = vm::dismissSheet)
        BookSheet.Settings -> BookSettingsSheet(
            settings = settings,
            ownSettings = state.usesOwnSettings,
            onChange = vm::setSettings,
            onOwnSettings = vm::setBookSettings,
            onReset = vm::resetSettings,
            onDismiss = vm::dismissSheet,
        )
        BookSheet.Notebook -> BookNotebookSheet(
            entries = notebook,
            onJump = jump,
            onDelete = { entry ->
                when (entry.kind) {
                    NotebookKind.Bookmark -> vm.removeBookmark(entry.id)
                    NotebookKind.Highlight -> vm.removeHighlight(entry.id)
                    NotebookKind.Note -> vm.removeNote(entry.id)
                }
            },
            onDismiss = vm::dismissSheet,
        )
        is BookSheet.Footnote -> BookFootnoteSheet(sheet.label, sheet.text, onDismiss = vm::dismissSheet)
        is BookSheet.NewNote -> BookNoteSheet(
            quote = sheet.quote,
            initial = "",
            onSave = { text ->
                vm.addNote(sheet.block, sheet.range, sheet.quote, text)
                vm.dismissSheet()
            },
            onDismiss = vm::dismissSheet,
        )
        is BookSheet.EditNote -> BookNoteSheet(
            quote = sheet.quote,
            initial = sheet.text,
            onSave = { text ->
                vm.editNote(sheet.noteId, text)
                vm.dismissSheet()
            },
            onDismiss = vm::dismissSheet,
        )
        is BookSheet.Marked -> {
            val highlight = annotations.highlights.firstOrNull { it.id == sheet.highlightId }
            val note = annotations.notes.firstOrNull { it.id == sheet.noteId }
            BookMarkedSheet(
                highlight = highlight,
                note = note,
                onColor = { color -> highlight?.let { vm.recolorHighlight(it.id, color) } },
                onEditNote = {
                    if (note != null) vm.showSheet(BookSheet.EditNote(note.id, note.quote, note.text))
                },
                onDelete = {
                    highlight?.let { vm.removeHighlight(it.id) }
                    note?.let { vm.removeNote(it.id) }
                    vm.dismissSheet()
                },
                onDismiss = vm::dismissSheet,
            )
        }
    }
}

/** The text of a block that has words, for quoting; other blocks have none. */
private fun ContentBlock.plainText(): String = when (this) {
    is ContentBlock.Heading -> text
    is ContentBlock.Paragraph -> text
    is ContentBlock.ListItem -> text
    is ContentBlock.Quote -> text
    is ContentBlock.Code -> text
    else -> ""
}

/** Holds the last selection while its bar fades out, so the bar keeps what it acts on. Not state: read in composition only. */
private class LastSelection {
    var value: LiveSelection? = null
}

/** The parts of [selection] as one highlight piece per block, with the text each covers. */
private fun selectedPieces(blocks: List<ContentBlock>, selection: LiveSelection): List<HighlightPiece> {
    val spans = selectionSpans(selection.start, selection.end) { blocks.getOrNull(it)?.plainText()?.length ?: 0 }
    return spans.map { span ->
        val text = blocks[span.block].plainText()
        HighlightPiece(span.block, span.start until span.end, text.substring(span.start, span.end))
    }
}

private fun quoteOf(pieces: List<HighlightPiece>): String = pieces.joinToString("\n") { it.quote }

/**
 * The selection's handles, and its action bar beside the start handle. Reads the selection itself,
 * so a drag recomposes only this layer. Copy and share use the selected text as it is in the book.
 * The bar stays after a clear just long enough to fade out.
 */
@Composable
private fun SelectionLayer(
    controller: BookSelectionController,
    blocks: List<ContentBlock>,
    palette: BookPalette,
    vm: BookViewModel,
) {
    val live = controller.selection
    val last = remember { LastSelection() }
    if (live != null) last.value = live
    val shown = last.value ?: return
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        SelectionOverlay(controller, handleColor = palette.selection.copy(alpha = 1f))
        SelectionBarHost(controller, visible = live != null) {
            SelectionBar(
                palette = palette,
                onHighlight = { color ->
                    vm.addHighlights(selectedPieces(blocks, shown), color)
                    controller.clear()
                },
                onNote = {
                    val pieces = selectedPieces(blocks, shown)
                    pieces.firstOrNull()?.let { first ->
                        vm.showSheet(BookSheet.NewNote(first.block, first.range, quoteOf(pieces)))
                    }
                    controller.clear()
                },
                onCopy = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("", quoteOf(selectedPieces(blocks, shown))))
                    controller.clear()
                },
                onShare = {
                    val intent = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, quoteOf(selectedPieces(blocks, shown)))
                    context.startActivity(Intent.createChooser(intent, null))
                    controller.clear()
                },
                onClose = { controller.clear() },
            )
        }
    }
}

private fun chapterLabel(manifest: BookManifest, block: Int): String {
    val title = manifest.chapterTitle(manifest.chapterIndexAt(block))
    return title.ifBlank { "Bookmark" }
}

@Composable
private fun BookTopBar(
    title: String,
    bookmarked: Boolean,
    paged: Boolean,
    tint: Color,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onNotebook: () -> Unit,
    onBookmark: () -> Unit,
    onToggleMode: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f).padding(start = 16.dp),
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
        )
        IconButton(onClick = onContents) { Icon(EzIcons.Menu, "Contents", tint = tint) }
        IconButton(onClick = onSettings) { Icon(EzIcons.TextT, "Reading settings", tint = tint) }
        IconButton(onClick = onNotebook) { Icon(EzIcons.Book, "Notebook", tint = tint) }
        IconButton(onClick = onBookmark) {
            Icon(if (bookmarked) EzIcons.Bookmark else EzIcons.BookmarkOutline, if (bookmarked) "Remove bookmark" else "Bookmark this place", tint = tint)
        }
        IconButton(onClick = onToggleMode) {
            Icon(if (paged) EzIcons.ListLines else EzIcons.SinglePage, if (paged) "Scroll instead" else "Read in pages", tint = tint)
        }
    }
}

@Composable
private fun BookScroll(
    blocks: List<ContentBlock>,
    settings: ReadingSettings,
    palette: BookPalette,
    typography: BookTypography,
    marks: Map<Int, List<MarkRange<TextMark>>>,
    actions: BookBlockActions,
    initial: Int,
    jumpTo: Int?,
    onJumpHandled: () -> Unit,
    onBlock: (Int) -> Unit,
) {
    // Lazy: only the blocks on screen are composed, however long the book.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial.coerceIn(0, blocks.lastIndex))
    LaunchedEffect(jumpTo) {
        val target = jumpTo ?: return@LaunchedEffect
        listState.scrollToItem(target.coerceIn(0, blocks.lastIndex))
        onJumpHandled()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { onBlock(it) }
    }
    // A selection survives scrolling: it is anchored to blocks and offsets, and its handles and bar are
    // placed again from the text whenever the list is laid out.
    val selection = actions.selection
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { if (selection.selection != null) selection.probes.invalidate() }
    }
    // While a drag is held near the top or bottom edge, scroll with a speed that grows with depth into the edge,
    // and re-extend the selection to the finger each frame, so blocks entering the screen are taken in.
    val density = LocalDensity.current
    val zonePx = with(density) { EdgeZone.toPx() }
    val maxSpeedPx = with(density) { EdgeMaxSpeed.toPx() }
    val dragging = selection.dragging
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        frameLoop { _, dt ->
            val speed = selection.verticalSpeed(zonePx, maxSpeedPx)
            if (speed != 0f) {
                listState.scrollBy(speed * dt)
                selection.refresh()
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = settings.margin.dp.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(blocks, key = { index, _ -> index }) { index, block ->
            Box(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                BookBlockView(
                    index = index,
                    block = block,
                    typography = typography,
                    palette = palette,
                    marks = marksOf(marks, index),
                    actions = actions,
                )
            }
        }
    }
}

/**
 * The paged reader for one layout. Keyed on the layout, so a settings change starts from a fresh
 * pagination (the previous one stays in the cache). Cutting pages runs in one effect that is
 * restarted, and so cancels any stale work, whenever the page or the jump target changes.
 */
@Composable
private fun BookPages(
    blocks: List<ContentBlock>,
    bookKey: String,
    vm: BookViewModel,
    settings: ReadingSettings,
    palette: BookPalette,
    typography: BookTypography,
    marks: Map<Int, List<MarkRange<TextMark>>>,
    actions: BookBlockActions,
    jumpTo: Int?,
    onJumpHandled: () -> Unit,
    onBlock: (Int) -> Unit,
) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val navPx = with(density) { 56.dp.roundToPx() }
    val paddingPx = with(density) { 20.dp.roundToPx() }
    val gapPx = with(density) { 10.dp.roundToPx() }
    val marginPx = with(density) { settings.margin.dp.dp.roundToPx() }
    val pageWidthPx = (constraints.maxWidth - 2 * marginPx).coerceAtLeast(1)
    val pageHeightPx = (constraints.maxHeight - navPx - 2 * paddingPx).coerceAtLeast(with(density) { 120.dp.roundToPx() })
    val layoutKey = BookLayoutKey(bookKey, settings.layout, pageWidthPx, pageHeightPx)
    val layout = remember(layoutKey) {
        vm.layouts.get(layoutKey) ?: BookLayout(blocks, pageHeightPx, gapPx).also { vm.layouts.put(layoutKey, it) }
    }
    val textMeasurer = rememberTextMeasurer()
    val measurer = remember(layout, typography, palette, pageWidthPx) {
        BookMeasurer(textMeasurer, density, typography, pageWidthPx, palette.onDark)
    }
    key(layoutKey) {
        PagedBook(
            layout = layout,
            measure = { block -> measurer.height(block) },
            palette = palette,
            typography = typography,
            marks = marks,
            actions = actions,
            marginDp = settings.margin.dp,
            jumpTo = jumpTo,
            onJumpHandled = onJumpHandled,
            onBlock = onBlock,
        )
    }
}

@Composable
private fun PagedBook(
    layout: BookLayout,
    measure: (ContentBlock) -> Int,
    palette: BookPalette,
    typography: BookTypography,
    marks: Map<Int, List<MarkRange<TextMark>>>,
    actions: BookBlockActions,
    marginDp: Int,
    jumpTo: Int?,
    onJumpHandled: () -> Unit,
    onBlock: (Int) -> Unit,
) {
    val index = layout.index
    val pagerState = rememberPagerState(pageCount = { layout.revision.let { index.pageCount + if (index.isComplete) 0 else 1 } })
    val scope = rememberCoroutineScope()

    // Turning a page by hand ends a selection. A page turned by a drag held at an edge keeps it.
    LaunchedEffect(pagerState.currentPage) {
        if (!actions.selection.dragging) actions.selection.clear()
    }

    // A drag held in the left or right edge zone for EDGE_DWELL_NANOS turns the page, and the selection
    // carries on onto it. Leaving the zone re-arms the dwell, so one hold turns one page.
    val selection = actions.selection
    val zonePx = with(LocalDensity.current) { EdgeZone.toPx() }
    val dragging = selection.dragging
    LaunchedEffect(dragging) {
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
                    pagerState.animateScrollToPage(target)
                    selection.refresh()
                }
            }
        }
    }

    // Cut pages while the reader is near the end of those cut, or the jump target is not yet on one.
    // Yields between pages so frames keep their deadline; restarts (cancelling the rest) on a new page or jump.
    LaunchedEffect(layout, jumpTo, pagerState.currentPage) {
        while (true) {
            val needJump = jumpTo != null && !index.covers(jumpTo)
            val needAhead = !index.isComplete && index.pageCount <= pagerState.currentPage + AHEAD
            if (!needJump && !needAhead) break
            layout.cutPages(pages = 1, measure = measure)
            yield()
        }
        if (jumpTo != null && index.covers(jumpTo)) {
            pagerState.scrollToPage(index.pageOf(jumpTo))
            onJumpHandled()
        }
    }
    // Reports the page's first block, but not while a jump is pending: until it lands, page 0 is not the reader's place.
    LaunchedEffect(pagerState.currentPage, layout.revision, jumpTo) {
        if (jumpTo == null && pagerState.currentPage < index.pageCount) onBlock(index.pageStart(pagerState.currentPage))
    }

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            pagerState,
            Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
        ) { page ->
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
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                enabled = pagerState.currentPage > 0,
            ) { Icon(EzIcons.ChevronLeft, "Previous page", tint = palette.text) }
            Text(
                if (index.isComplete) "${pagerState.currentPage + 1} / ${index.pageCount}" else "${pagerState.currentPage + 1}",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                enabled = !index.isComplete || pagerState.currentPage < index.pageCount - 1,
            ) { Icon(EzIcons.ChevronRight, "Next page", tint = palette.text) }
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
    val pad = Modifier.fillMaxSize().padding(horizontal = marginDp.dp, vertical = 20.dp)
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

private const val AHEAD = 2

/** How long a drag must be held in a side edge zone before the page turns. */
private const val EDGE_DWELL_NANOS = 500_000_000L

/** One shared empty list, so blocks without marks keep the same argument across recompositions. */
private val NO_MARKS: List<MarkRange<TextMark>> = emptyList()

private fun marksOf(marks: Map<Int, List<MarkRange<TextMark>>>, block: Int): List<MarkRange<TextMark>> =
    marks[block] ?: NO_MARKS
