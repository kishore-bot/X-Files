package com.xfiles.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.book.BookManifest
import com.xfiles.documents.book.TextMark
import com.xfiles.documents.book.bookKey
import com.xfiles.documents.structure.StructuredDocument

/**
 * An e-book, read in pages or as one scroll. Pages turn with a slide; the left and right thirds
 * of a tap turn them and the centre shows or hides the bars. Contents, the "Aa" settings, find,
 * highlights, notes, footnotes, pictures and the notebook are all sheets over the book. A long
 * press selects text: handles adjust it, and a bar below it highlights, notes, copies or shares it.
 * Each book opens where it was left. This file wires the ViewModel to the reader's parts.
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

    val palette = remember(state.settings.theme) { bookPalette(state.settings.theme) }
    val typography = remember(state.settings.layout, palette) { bookTypography(state.settings, palette) }
    val annotations = state.annotations
    // Keyed on the lists themselves: a page turn changes only the position, and must not rebuild these.
    val marks = remember(annotations.highlights, annotations.notes) { annotations.marksByBlock() }
    val haptics = rememberHaptics()
    val hapticFeedback = LocalHapticFeedback.current

    // The live selection and what hit-tests the text on screen. Replaced when the book changes.
    val probes = remember(key) { TextProbes() }
    val selectionState = remember(key) { mutableStateOf<LiveSelection?>(null) }
    val selection = remember(key, hapticFeedback) { BookSelectionController(probes, selectionState, hapticFeedback) }

    // The block the reader is at; set by whichever view is showing, and saved as the position.
    var current by remember(key) { mutableIntStateOf(annotations.position) }
    val currentBlock by rememberUpdatedState(current)
    // A block to bring into view; cleared once it is shown.
    var jumpTo by remember(key) { mutableStateOf<Int?>(annotations.position.takeIf { it > 0 }) }
    val nav = remember(key) { BookPageNav() }
    val onBlock: (Int) -> Unit = { block ->
        current = block
        vm.savePosition(block)
    }
    val jump: (Int) -> Unit = { block ->
        vm.dismissSheet()
        jumpTo = block
    }

    val actions = remember(blocks, manifest, vm, selection, haptics) {
        BookBlockActions(
            onNoteLink = { href ->
                val id = href.removePrefix(BookManifest.NOTE_PREFIX)
                val text = manifest?.notes?.get(id) ?: "This note is not in the book's files."
                haptics.confirm()
                vm.showSheet(BookSheet.Footnote(label = "Note", text = text))
            },
            onInternalLink = { href ->
                // Only a link to a place in this book moves the reader, and system back returns from it.
                manifest?.blockOf(href)?.let { target ->
                    haptics.confirm()
                    selection.clear()
                    vm.pushJump(currentBlock)
                    jump(target)
                }
            },
            onExternalLink = { url -> vm.onEvent(BookEvent.OpenExternal(url)) },
            onMark = { block, mark ->
                haptics.tick()
                vm.showSheet(
                    when (mark) {
                        is TextMark.Highlighted -> BookSheet.Marked(block, mark.id, null)
                        is TextMark.Noted -> BookSheet.Marked(block, null, mark.id)
                    },
                )
            },
            onPicture = { image -> vm.showSheet(BookSheet.Picture(image)) },
            selection = selection,
        )
    }

    BackHandler(enabled = state.backStack.isNotEmpty()) {
        vm.popJump()?.let(jump)
    }

    val chrome = BookChromeActions(
        onSearch = { vm.showSheet(BookSheet.Search) },
        onContents = { vm.showSheet(BookSheet.Contents) },
        onNotebook = { vm.showSheet(BookSheet.Notebook) },
        onBookmark = {
            haptics.tick()
            vm.toggleBookmark(current, manifest?.let { chapterLabel(it, current) } ?: "Bookmark")
        },
        onSettings = { vm.showSheet(BookSheet.Settings) },
        onBack = { vm.popJump()?.let(jump) },
    )

    BookReaderBody(
        blocks = blocks,
        manifest = manifest,
        bookKey = key,
        title = manifest?.title?.takeIf { it.isNotBlank() } ?: document.info.name,
        bookmarked = annotations.isBookmarked(current),
        state = state,
        vm = vm,
        palette = palette,
        typography = typography,
        marks = marks,
        actions = actions,
        nav = nav,
        haptics = haptics,
        current = current,
        jumpTo = jumpTo,
        onJumpHandled = { jumpTo = null },
        onBlock = onBlock,
        onJump = jump,
        chrome = chrome,
    )

    BookSheetHost(
        sheet = state.sheet,
        vm = vm,
        state = state,
        annotations = annotations,
        manifest = manifest,
        blocks = blocks,
        haptics = haptics,
        onJump = jump,
        onMode = { mode ->
            // The other view opens where this one is.
            selection.clear()
            jumpTo = current
            vm.onEvent(BookEvent.SetMode(mode))
        },
    )
}

private fun chapterLabel(manifest: BookManifest, block: Int): String {
    val title = manifest.chapterTitle(manifest.chapterIndexAt(block))
    return title.ifBlank { "Bookmark" }
}
