package com.xfiles.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfMatchBox
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import kotlin.math.roundToInt

/**
 * The page after [page] when stepping by [direction] (-1 or 1). A spread moves by a pair, so the
 * next spread starts two pages on. Null at either end of the document.
 */
internal fun pdfStepTarget(page: Int, direction: Int, twoPage: Boolean, pageCount: Int): Int? {
    val target = if (twoPage) (page - page % 2) + 2 * direction else page + direction
    return target.takeIf { it in 0 until pageCount }
}

/** The slider's range for [pageCount] pages (0-based; never empty). */
internal fun pdfSliderRange(pageCount: Int): ClosedFloatingPointRange<Float> =
    0f..(pageCount - 1).coerceAtLeast(1).toFloat()

/** The 0-based page a slider [value] stands for, kept inside the document. */
internal fun pdfSliderPage(value: Float, pageCount: Int): Int =
    if (value.isNaN()) 0 else value.roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))

/**
 * The bottom bar of a PDF: previous, "n / N" (tap to type a page), next, the two-page and grid
 * toggles, and a page slider underneath. Turns render once, when the slider is released.
 */
@Composable
internal fun PdfPageBar(
    page: Int,
    pageCount: Int,
    twoPage: Boolean,
    busy: Boolean,
    canSpread: Boolean,
    onPage: (Int) -> Unit,
    onJump: () -> Unit,
    onTwoPage: (Boolean) -> Unit,
    onGrid: () -> Unit,
) {
    val previous = pdfStepTarget(page, -1, twoPage, pageCount)
    val next = pdfStepTarget(page, 1, twoPage, pageCount)
    Column(Modifier.fillMaxWidth().glassBar()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { previous?.let(onPage) }, enabled = !busy && previous != null) {
                Icon(EzIcons.ChevronLeft, "Previous page")
            }
            Box(
                Modifier
                    .defaultMinSize(minWidth = 72.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Go to page", onClick = onJump)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("${page + 1} / $pageCount")
            }
            IconButton(onClick = { next?.let(onPage) }, enabled = !busy && next != null) {
                Icon(EzIcons.ChevronRight, "Next page")
            }
            if (twoPage) {
                FilledTonalIconButton(onClick = { onTwoPage(false) }, enabled = !busy && canSpread) {
                    Icon(EzIcons.TwoPages, "Single page")
                }
            } else {
                IconButton(onClick = { onTwoPage(true) }, enabled = !busy && canSpread) {
                    Icon(EzIcons.TwoPages, "Two pages")
                }
            }
            IconButton(onClick = onGrid, enabled = !busy && canSpread) {
                Icon(EzIcons.Grid, "Page grid")
            }
        }
        PdfPageSlider(page, pageCount, enabled = !busy, onSeek = onPage, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/**
 * A page slider with a bubble showing the page under the thumb while dragging. Nothing renders while
 * dragging; the chosen page is requested once, on release.
 */
@Composable
internal fun PdfPageSlider(page: Int, pageCount: Int, enabled: Boolean, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (pageCount < 2) return
    var dragging by remember(pageCount) { mutableStateOf<Float?>(null) }
    var bubble by remember { mutableStateOf(IntSize.Zero) }
    val shown = dragging?.let { pdfSliderPage(it, pageCount) } ?: page
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxWidth.toPx() }
        val inset = with(density) { 24.dp.toPx() }
        val fraction = shown / (pageCount - 1f)
        val centerX = inset + fraction * (trackPx - 2 * inset)
        Slider(
            value = dragging ?: page.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(pdfSliderPage(it, pageCount)) }
                dragging = null
            },
            valueRange = pdfSliderRange(pageCount),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Page slider" },
        )
        if (dragging != null) {
            Text(
                "${shown + 1}",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            (centerX - bubble.width / 2f).roundToInt(),
                            -(bubble.height + 8.dp.roundToPx()),
                        )
                    }
                    .onSizeChanged { bubble = it }
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** "Go to page": a field for a 1-based page number, checked against the document. */
@Composable
internal fun PdfJumpDialog(page: Int, pageCount: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    val text = rememberTextFieldState(initialText = (page + 1).toString())
    val target = text.text.toString().trim().toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                state = text,
                lineLimits = TextFieldLineLimits.SingleLine,
                label = { Text("Page 1 to $pageCount") },
                isError = text.text.isNotEmpty() && target == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    target?.let { onJump(it - 1) }
                    onDismiss()
                },
                enabled = target != null,
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Every page as a small thumbnail. Cells load lazily: a cell that scrolls away leaves composition and
 * cancels its request before the renderer reaches it. Tapping a page opens it.
 */
@Composable
internal fun PdfPageGrid(
    pageCount: Int,
    current: Int,
    load: suspend (Int) -> Bitmap?,
    onOpen: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val outline = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Pages", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onClose) { Icon(EzIcons.Close, "Close page grid") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            state = rememberLazyGridState(initialFirstVisibleItemIndex = current.coerceAtLeast(0)),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(count = pageCount, key = { it }) { index ->
                val active = index == current
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.72f)
                            .clip(shape)
                            .background(Color.White)
                            .border(if (active) 2.dp else 1.dp, if (active) primary else outline, shape)
                            .clickable(role = Role.Button, onClickLabel = "Go to page ${index + 1}") { onOpen(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        PdfThumbnail(index, load)
                    }
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PdfThumbnail(index: Int, load: suspend (Int) -> Bitmap?) {
    val image by produceState<ImageBitmap?>(null, index, load) {
        value = load(index)?.asImageBitmap()
    }
    image?.let { Image(it, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
}

/** Two pages side by side (left is the lower page number). Each page keeps its own pinch and pan. */
@Composable
internal fun PdfSpread(page: FilePreview.Pdf, facing: FilePreview.Pdf, rotation: Int, highlights: (Int) -> List<PdfMatchBox>) {
    val (left, right) = if (page.page < facing.page) page to facing else facing to page
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderImage(left.bitmap, "Page ${left.page + 1}", rotation, highlights(left.page), Modifier.weight(1f).fillMaxHeight())
        ReaderImage(right.bitmap, "Page ${right.page + 1}", rotation, highlights(right.page), Modifier.weight(1f).fillMaxHeight())
    }
}
