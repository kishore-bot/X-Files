package com.xfiles.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import com.xfiles.design.glassPanel
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.StructuredDocument
import kotlinx.coroutines.launch

/**
 * An e-book in pages: swipe or use the arrows to turn. Pages are cut lazily: each page measures the
 * blocks after the previous page's end until the screen is full and records where the next page
 * starts, so a long book is never laid out all at once. A block taller than a page (a very long
 * paragraph or a big picture) gets a page of its own that scrolls.
 */
@Composable
internal fun BookPreview(document: StructuredDocument) {
    val blocks = document.blocks
    if (blocks.isEmpty()) {
        Text("This book has no readable content.")
        return
    }
    var paged by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().glassPanel(RoundedCornerShape(16.dp), elevation = 3.dp)) {
            if (paged) BookPages(blocks) else ScrolledBook(blocks)
        }
        Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (paged) "Pages" else "Scrolling",
                Modifier.weight(1f).padding(start = 16.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(onClick = { paged = !paged }) {
                Icon(if (paged) EzIcons.ListLines else EzIcons.SinglePage, if (paged) "Scroll instead" else "Read in pages")
            }
        }
    }
}

@Composable
private fun ScrolledBook(blocks: List<ContentBlock>) {
    // Lazy: only the blocks on screen are composed, however long the book.
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(blocks, key = { index, _ -> index }) { _, block ->
            Box(Modifier.widthIn(max = 680.dp).fillMaxWidth()) { DocumentBlock(block) }
        }
    }
}

@Composable
private fun BookPages(blocks: List<ContentBlock>) = BoxWithConstraints(Modifier.fillMaxSize()) {
    // The arrows row takes ~56 dp below the pages; the cut depends on the remaining size.
    val pageHeight = constraints.maxHeight - with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.roundToPx() }
    key(blocks, constraints.maxWidth, pageHeight) { PagedBook(blocks, pageHeight) }
}

@Composable
private fun PagedBook(blocks: List<ContentBlock>, pageHeight: Int) {
    // starts[i] = first block of page i; grows as pages are measured.
    val starts = remember { mutableStateListOf(0) }
    var finished by remember { mutableStateOf(false) }
    val pager = rememberPagerState { if (finished) starts.size else starts.size + 1 }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
                val start = starts.getOrNull(index)
                if (start == null) {
                    Box(Modifier.fillMaxSize())
                } else {
                    BookPage(blocks, start, pageHeight) { next ->
                        // Record where the following page starts, once.
                        if (index == starts.lastIndex) {
                            if (next >= blocks.size) finished = true else starts.add(next)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                Icon(EzIcons.ChevronLeft, "Previous page")
            }
            Text(
                if (finished) "${pager.currentPage + 1} / ${starts.size}" else "${pager.currentPage + 1}",
                color = EzColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                enabled = !finished || pager.currentPage < starts.lastIndex,
            ) { Icon(EzIcons.ChevronRight, "Next page") }
        }
    }
}

/**
 * Measures blocks from [start] until the next one would overflow [pageHeight], places those, and
 * reports the index after the last one placed through [onMeasured].
 */
@Composable
private fun BookPage(blocks: List<ContentBlock>, start: Int, pageHeight: Int, onMeasured: (Int) -> Unit) {
    val padding = with(androidx.compose.ui.platform.LocalDensity.current) { 20.dp.roundToPx() }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val width = (constraints.maxWidth - 2 * padding).coerceAtLeast(0)
        val available = pageHeight - 2 * padding
        val childConstraints = Constraints(maxWidth = width)
        val placed = ArrayList<Pair<androidx.compose.ui.layout.Placeable, Int>>()
        var used = 0
        var index = start
        while (index < blocks.size) {
            val placeable = subcompose(index) { Box(Modifier.fillMaxWidth()) { DocumentBlock(blocks[index]) } }
                .first().measure(childConstraints)
            val needed = placeable.height + if (placed.isEmpty()) 0 else gap
            if (placed.isNotEmpty() && used + needed > available) break
            placed += placeable to used + (if (placed.isEmpty()) 0 else gap)
            used += needed
            index++
            if (used >= available) break
        }
        // A single block taller than the page: show it alone, scrollable.
        val oversized = placed.size == 1 && placed.single().first.height > available
        val scroller = if (oversized) {
            val single = start
            subcompose("scroll-$single") {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DocumentBlock(blocks[single]) }
            }.first().measure(Constraints.fixed(width, available.coerceAtLeast(0)))
        } else null
        onMeasured(index)
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (scroller != null) scroller.place(padding, padding)
            else placed.forEach { (placeable, y) -> placeable.place(padding, padding + y) }
        }
    }
}
