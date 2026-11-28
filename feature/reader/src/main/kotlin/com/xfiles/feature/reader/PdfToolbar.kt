@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xfiles.feature.reader

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzControlSheet
import com.xfiles.design.EzIcons
import com.xfiles.design.LocalFilyOpen
import com.xfiles.design.EzControlBar
import com.xfiles.design.rememberHaptics

/**
 * Compact PDF navigation. The page strip and scrubber open on demand in a sheet.
 */
@Composable
internal fun PdfToolbar(
    page: Int,
    pageCount: Int,
    zoom: Int,
    grid: Boolean,
    colorFilter: ColorFilter?,
    thumbnail: suspend (Int) -> Bitmap?,
    onPage: (Int) -> Unit,
    onJump: () -> Unit,
    onZoom: (Int) -> Unit,
    modifier: Modifier = Modifier,
    settingsOpen: Boolean = false,
    onEngage: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    val previous = if (page > 0) page - 1 else null
    val next = if (page < pageCount - 1) page + 1 else null
    var showPages by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val filyOpen = LocalFilyOpen.current
    LaunchedEffect(filyOpen, settingsOpen) {
        if (filyOpen || settingsOpen) showPages = false
    }
    EzControlBar(modifier) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haptics.tick(); previous?.let(onPage) }, enabled = previous != null) {
                    Icon(EzIcons.ChevronLeft, "Previous page")
                }
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Go to page") {
                            haptics.tick()
                            onEngage()
                            onJump()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${page + 1} / $pageCount", style = MaterialTheme.typography.labelLarge)
                    Icon(EzIcons.DropDown, null)
                }
                IconButton(onClick = { haptics.tick(); next?.let(onPage) }, enabled = next != null) {
                    Icon(EzIcons.ChevronRight, "Next page")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { haptics.tick(); onZoom(zoom - PDF_ZOOM_STEP) },
                    enabled = !grid && zoom > PDF_MIN_ZOOM,
                ) { Icon(EzIcons.Minus, "Zoom out") }
                Text("$zoom%", style = MaterialTheme.typography.labelLarge)
                IconButton(
                    onClick = { haptics.tick(); onZoom(zoom + PDF_ZOOM_STEP) },
                    enabled = !grid && zoom < PDF_MAX_ZOOM,
                ) { Icon(EzIcons.Plus, "Zoom in") }
                if (pageCount > 1 && !grid) {
                    IconButton(onClick = { onEngage(); showPages = true }) { Icon(EzIcons.ListLines, "Page thumbnails") }
                }
            }
        }
    }
    if (showPages && !grid && !filyOpen && !settingsOpen) {
        PdfPageSheet(page, pageCount, thumbnail, colorFilter, enabled = true, onPage = { target ->
            showPages = false
            onPage(target)
        }, onDismiss = { showPages = false })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PdfPageSheet(
    page: Int,
    pageCount: Int,
    thumbnail: suspend (Int) -> Bitmap?,
    colorFilter: ColorFilter?,
    enabled: Boolean,
    onPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    EzControlSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Pages", style = MaterialTheme.typography.titleMedium)
            PdfPageSlider(page, pageCount, enabled, onPage)
            PdfThumbnailStrip(pageCount, page, thumbnail, colorFilter, enabled, onPage)
        }
    }
}

/** The thumbnails of every page in one row; the current one is outlined and the row follows it. */
@Composable
private fun PdfThumbnailStrip(
    pageCount: Int,
    page: Int,
    thumbnail: suspend (Int) -> Bitmap?,
    colorFilter: ColorFilter?,
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
                    Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = colorFilter)
                }
            }
        }
    }
}
