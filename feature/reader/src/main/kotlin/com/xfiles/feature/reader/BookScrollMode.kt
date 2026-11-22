package com.xfiles.feature.reader

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xfiles.documents.book.MarkRange
import com.xfiles.documents.book.ReadingSettings
import com.xfiles.documents.book.TextMark
import com.xfiles.documents.structure.ContentBlock
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The scroll reader: the book as one long column. Lazy, so only the blocks on screen are composed,
 * however long the book. Reports the first visible block as the reader's place.
 */
@Composable
internal fun BookScroll(
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
        contentPadding = PaddingValues(
            start = settings.margin.dp.dp,
            end = settings.margin.dp.dp,
            top = BookInsetTop,
            bottom = BookInsetBottom,
        ),
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
