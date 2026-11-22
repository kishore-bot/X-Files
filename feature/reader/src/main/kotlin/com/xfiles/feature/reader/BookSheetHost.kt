package com.xfiles.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import com.xfiles.documents.book.BookAnnotations
import com.xfiles.documents.book.BookManifest
import com.xfiles.documents.book.NotebookKind
import com.xfiles.documents.book.ReadingSettings
import com.xfiles.design.Haptics
import com.xfiles.documents.structure.ContentBlock

/**
 * Shows whichever sheet is open. Each sheet is stateless: it gets the book's data and the
 * ViewModel's actions, and reports what the reader chose.
 */
@Composable
internal fun BookSheetHost(
    sheet: BookSheet,
    vm: BookViewModel,
    state: BookUiState,
    annotations: BookAnnotations,
    manifest: BookManifest?,
    blocks: List<ContentBlock>,
    haptics: Haptics,
    onJump: (Int) -> Unit,
    onMode: (BookReadingMode) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val settings: ReadingSettings = state.settings
    when (sheet) {
        BookSheet.None -> Unit
        BookSheet.Contents -> BookContentsSheet(manifest, onJump = onJump, onDismiss = vm::dismissSheet)
        BookSheet.Settings -> BookSettingsSheet(
            settings = settings,
            ownSettings = state.usesOwnSettings,
            mode = state.mode,
            onMode = onMode,
            onChange = vm::setSettings,
            onOwnSettings = vm::setBookSettings,
            onReset = vm::resetSettings,
            onDismiss = vm::dismissSheet,
        )
        BookSheet.Notebook -> BookNotebookSheet(
            entries = annotations.notebook(),
            onJump = onJump,
            onDelete = { entry ->
                when (entry.kind) {
                    NotebookKind.Bookmark -> vm.removeBookmark(entry.id)
                    NotebookKind.Highlight -> vm.removeHighlight(entry.id)
                    NotebookKind.Note -> vm.removeNote(entry.id)
                }
            },
            onDismiss = vm::dismissSheet,
        )
        BookSheet.Search -> BookSearchSheet(
            state = state.search,
            onQuery = { vm.search(blocks, it) },
            onOpen = { hit ->
                vm.clearSearch()
                onJump(hit.block)
            },
            onDismiss = vm::dismissSheet,
        )
        is BookSheet.ExternalLink -> BookExternalLinkSheet(
            url = sheet.url,
            onOpen = {
                haptics.confirm()
                runCatching { uriHandler.openUri(sheet.url) }
                vm.dismissSheet()
            },
            onDismiss = vm::dismissSheet,
        )
        is BookSheet.Picture -> BookPictureViewer(image = sheet.image, onDismiss = vm::dismissSheet)
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
