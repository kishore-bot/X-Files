package com.xfiles.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.xfiles.design.Haptics
import com.xfiles.documents.book.selectionSpans
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.feature.reader.domain.plainText

/** Holds the last selection while its bar fades out, so the bar keeps what it acts on. Not state: read in composition only. */
private class LastSelection {
    var value: LiveSelection? = null
}

/** The parts of [selection] as one highlight piece per block, with the text each covers. */
internal fun selectedPieces(blocks: List<ContentBlock>, selection: LiveSelection): List<HighlightPiece> {
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
 * The bar stays after a clear just long enough to fade out. A highlight is a long-press cue.
 */
@Composable
internal fun SelectionLayer(
    controller: BookSelectionController,
    blocks: List<ContentBlock>,
    palette: BookPalette,
    vm: BookViewModel,
    haptics: Haptics,
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
                    haptics.longPress()
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
