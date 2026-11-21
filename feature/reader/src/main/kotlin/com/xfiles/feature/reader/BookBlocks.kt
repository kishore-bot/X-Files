package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.xfiles.documents.book.BookManifest
import com.xfiles.documents.book.MarkRange
import com.xfiles.documents.book.SegmentIndex
import com.xfiles.documents.book.TextMark
import com.xfiles.documents.book.spanInBlock
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.TextSpan

/** What a block reports back: a footnote tapped, a link into the book, a highlight or note tapped. */
internal class BookBlockActions(
    val onNoteLink: (href: String) -> Unit,
    val onInternalLink: (href: String) -> Unit,
    val onMark: (block: Int, mark: TextMark) -> Unit,
    val selection: BookSelectionController,
)

/** Width of a table cell in the book, before its padding. Shared with the page measurer. */
internal val BookCellWidth = 140.dp

/**
 * One block of the book. Text blocks take part in selection: a long press selects the word under
 * the finger, and a drag, which may cross into other text blocks on the page, extends it. A tap on
 * a footnote or a link, or on a marked range, reports it. Table cells report their links too.
 * Other blocks (pictures) are drawn by the shared document blocks.
 */
@Composable
internal fun BookBlockView(
    index: Int,
    block: ContentBlock,
    typography: BookTypography,
    palette: BookPalette,
    marks: List<MarkRange<TextMark>>,
    actions: BookBlockActions,
) {
    when (block) {
        is ContentBlock.Heading -> BookText(
            index, block.text, block.spans, typography.heading(block.level), palette, marks, actions,
            Modifier.semantics { heading() },
        )
        is ContentBlock.Paragraph -> BookText(index, block.text, block.spans, typography.body, palette, marks, actions)
        is ContentBlock.Quote -> Box(Modifier.fillMaxWidth().padding(12.dp)) {
            BookText(index, block.text, block.spans, typography.quote, palette, marks, actions)
        }
        is ContentBlock.ListItem -> Row(
            Modifier.padding(start = (block.depth.coerceIn(0, 8) * 14).dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val marker = block.marker ?: "•"
            if (marker.isNotEmpty()) Text(marker, style = typography.body, color = palette.secondaryText)
            BookText(
                index, block.text, block.spans, typography.body, palette, marks, actions,
                Modifier.weight(1f),
            )
        }
        is ContentBlock.Code -> Text(
            text = if (block.firstLine == null) block.text else block.text.lineSequence().mapIndexed { line, text ->
                "${block.firstLine!! + line}  $text"
            }.joinToString("\n"),
            modifier = Modifier.fillMaxWidth().background(palette.codeFill, RoundedCornerShape(8.dp)).padding(12.dp),
            style = typography.code,
        )
        ContentBlock.Divider -> HorizontalDivider(color = palette.secondaryText.copy(alpha = 0.3f))
        is ContentBlock.TableRow -> BookTableRow(block, typography, palette, actions)
        else -> CompositionLocalProvider(LocalContentColor provides palette.text) {
            DocumentBlock(block)
        }
    }
}

@Composable
private fun BookText(
    index: Int,
    text: String,
    spans: List<TextSpan>,
    style: TextStyle,
    palette: BookPalette,
    rawMarks: List<MarkRange<TextMark>>,
    actions: BookBlockActions,
    modifier: Modifier = Modifier,
) {
    val currentActions by rememberUpdatedState(actions)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val probes = actions.selection.probes

    // Registers this block's laid-out text for hit-testing while it is on screen.
    LaunchedEffect(coords, layout, text) {
        val c = coords
        val l = layout
        if (c != null && l != null) probes.put(index, TextProbe(c, l, text))
    }
    // Leaving the screen drops the probe; a drag that this block started ends with it, so nothing keeps scrolling.
    DisposableEffect(index, actions.selection) {
        onDispose {
            probes.remove(index)
            actions.selection.release(index)
        }
    }

    val marks = remember(text, rawMarks) { SegmentIndex.build(rawMarks, text.length) }
    val currentMarks by rememberUpdatedState(marks)
    val live = actions.selection.selection
    val selected = live?.let { spanInBlock(it.start, it.end, index, text.length) }
    val annotated = remember(text, spans, marks, selected, palette) {
        val base = richText(text, spans, palette.onDark)
        buildAnnotatedString {
            append(base)
            for (segment in marks.segments()) {
                val highlight = segment.values.filterIsInstance<TextMark.Highlighted>().lastOrNull()
                if (highlight != null) {
                    addStyle(SpanStyle(background = highlight.color.paint(palette.onDark)), segment.start, segment.end)
                }
                if (segment.values.any { it is TextMark.Noted }) {
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), segment.start, segment.end)
                }
            }
            selected?.let { range ->
                val end = range.last + 1
                if (range.first < end && end <= text.length) {
                    addStyle(SpanStyle(background = palette.selection), range.first, end)
                }
            }
        }
    }

    Text(
        annotated,
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords = it }
            .pointerInput(text, spans) {
                detectTapGestures { position ->
                    // The first tap after a selection only clears it.
                    if (currentActions.selection.selection != null) {
                        currentActions.selection.clear()
                        return@detectTapGestures
                    }
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    val link = linkAt(spans, offset)
                    if (link != null) {
                        currentActions.follow(link)
                        return@detectTapGestures
                    }
                    currentMarks.at(offset).lastOrNull()?.let { currentActions.onMark(index, it) }
                }
            }
            .pointerInput(text, index) {
                // The drag may leave this block; the pointer stays with it, and the selection maps it to whichever block it is over.
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        coords?.let { currentActions.selection.begin(it.localToWindow(position), index) }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        coords?.let { currentActions.selection.extendTo(it.localToWindow(change.position)) }
                    },
                    onDragEnd = { currentActions.selection.endDrag() },
                    onDragCancel = { currentActions.selection.endDrag() },
                )
            },
        style = style,
        onTextLayout = { layout = it },
    )
}

/**
 * A table row of the book. Each cell is rich text: its links are tapped like the book's own, and
 * a footnote or a link into the book goes to the same handlers. Cells are not selectable.
 */
@Composable
private fun BookTableRow(
    block: ContentBlock.TableRow,
    typography: BookTypography,
    palette: BookPalette,
    actions: BookBlockActions,
) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        block.cells.forEachIndexed { column, cell ->
            BookCell(cell, block.cellSpans.getOrNull(column).orEmpty(), block.header, typography, palette, actions)
        }
    }
}

@Composable
private fun BookCell(
    text: String,
    spans: List<TextSpan>,
    header: Boolean,
    typography: BookTypography,
    palette: BookPalette,
    actions: BookBlockActions,
) {
    val currentActions by rememberUpdatedState(actions)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val styled = remember(text, spans, palette) { richText(text, spans, palette.onDark) }
    Box(
        Modifier
            .width(BookCellWidth)
            .border(0.5.dp, palette.secondaryText.copy(alpha = 0.3f))
            .then(if (header) Modifier.background(palette.codeFill) else Modifier)
            .padding(8.dp),
    ) {
        Text(
            styled,
            Modifier
                .fillMaxWidth()
                .pointerInput(text, spans) {
                    detectTapGestures { position ->
                        if (currentActions.selection.selection != null) {
                            currentActions.selection.clear()
                            return@detectTapGestures
                        }
                        val result = layout ?: return@detectTapGestures
                        linkAt(spans, result.getOffsetForPosition(position))?.let { currentActions.follow(it) }
                    }
                },
            style = if (header) typography.body.copy(fontWeight = FontWeight.Bold) else typography.body,
            onTextLayout = { layout = it },
        )
    }
}

/** The link under [offset] in a block's spans: a footnote wins over a link into the book. Null when there is none. */
internal fun linkAt(spans: List<TextSpan>, offset: Int): TextSpan? {
    var book: TextSpan? = null
    for (span in spans) {
        if (offset < span.start || offset >= span.end) continue
        val link = span.link ?: continue
        if (link.startsWith(BookManifest.NOTE_PREFIX)) return span
        if (book == null && link.startsWith(BookManifest.LINK_PREFIX)) book = span
    }
    return book
}

/** Reports a tapped link span to the handler for its kind. */
private fun BookBlockActions.follow(span: TextSpan) {
    val link = span.link ?: return
    when {
        link.startsWith(BookManifest.NOTE_PREFIX) -> onNoteLink(link)
        link.startsWith(BookManifest.LINK_PREFIX) -> onInternalLink(link.removePrefix(BookManifest.LINK_PREFIX))
    }
}
