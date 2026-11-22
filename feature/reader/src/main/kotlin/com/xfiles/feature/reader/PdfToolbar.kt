package com.xfiles.feature.reader

import androidx.compose.foundation.layout.fillMaxSize
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import com.xfiles.design.rememberHaptics

/**
 * The PDF's bottom controls, as the EzFiles reader lays them out: a page slider with the counter, a strip of
 * page thumbnails, then one bar with previous, "n / N" (opens the jump dialog through [onJump]), next, zoom
 * out, the zoom percentage, zoom in, and the view modes (single, two pages, grid).
 */
@Composable
internal fun PdfToolbar(
    page: Int,
    pageCount: Int,
    zoom: Int,
    twoUp: Boolean,
    grid: Boolean,
    busy: Boolean,
    thumbnail: suspend (Int) -> Bitmap?,
    onPage: (Int) -> Unit,
    onJump: () -> Unit,
    onZoom: (Int) -> Unit,
    onTwoUp: (Boolean) -> Unit,
    onGrid: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val previous = if (page > 0) page - 1 else null
    val next = if (page < pageCount - 1) page + 1 else null
    Column(
        modifier
            .fillMaxWidth()
            .glassBar()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pageCount > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PdfPageSlider(
                    page = page,
                    pageCount = pageCount,
                    enabled = !busy,
                    onSeek = { target ->
                        haptics.tick()
                        onPage(target)
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${page + 1} / $pageCount",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            PdfThumbnailStrip(pageCount, page, thumbnail, enabled = !busy, onPage = onPage)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            IconButton(onClick = { haptics.tick(); previous?.let(onPage) }, enabled = !busy && previous != null) {
                Icon(EzIcons.ChevronLeft, "Previous page")
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Go to page") {
                        haptics.tick()
                        onJump()
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${page + 1} / $pageCount", style = MaterialTheme.typography.labelLarge)
                Icon(EzIcons.DropDown, "Go to page")
            }
            IconButton(onClick = { haptics.tick(); next?.let(onPage) }, enabled = !busy && next != null) {
                Icon(EzIcons.ChevronRight, "Next page")
            }
            IconButton(
                onClick = { haptics.tick(); onZoom(zoom - PDF_ZOOM_STEP) },
                enabled = !grid && zoom > PDF_MIN_ZOOM,
            ) { Icon(EzIcons.Minus, "Zoom out") }
            Text("$zoom%", style = MaterialTheme.typography.labelLarge)
            IconButton(
                onClick = { haptics.tick(); onZoom(zoom + PDF_ZOOM_STEP) },
                enabled = !grid && zoom < PDF_MAX_ZOOM,
            ) { Icon(EzIcons.Plus, "Zoom in") }
            ModeButton(EzIcons.SinglePage, "Single page", selected = !twoUp && !grid) {
                haptics.segment()
                onGrid(false)
                onTwoUp(false)
            }
            ModeButton(EzIcons.TwoPages, "Two pages", selected = twoUp && !grid) {
                haptics.segment()
                onGrid(false)
                onTwoUp(true)
            }
            ModeButton(EzIcons.Grid, "Page grid", selected = grid) {
                haptics.segment()
                onGrid(true)
            }
        }
    }
}

@Composable
private fun ModeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The thumbnails of every page in one row; the current one is outlined and the row follows it. */
@Composable
private fun PdfThumbnailStrip(
    pageCount: Int,
    page: Int,
    thumbnail: suspend (Int) -> Bitmap?,
    enabled: Boolean,
    onPage: (Int) -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(page) { list.animateScrollToItem((page - 2).coerceAtLeast(0)) }
    LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(count = pageCount) { index ->
            val bitmap by produceState<Bitmap?>(null, index) { value = thumbnail(index) }
            val selected = index == page
            Box(
                Modifier
                    .width(40.dp)
                    .size(width = 40.dp, height = 54.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(
                        if (selected) 2.dp else 0.5.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(4.dp),
                    )
                    .clickable(enabled = enabled) { onPage(index) },
            ) {
                bitmap?.let {
                    Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
    }
}
