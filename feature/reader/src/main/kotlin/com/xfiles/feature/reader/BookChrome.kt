package com.xfiles.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.floatingGlass
import com.xfiles.design.glassBar

/** Space above a page or the first row of the scroll, where the top bar floats when shown. */
internal val BookInsetTop = 60.dp

/** Space below a page or the last row of the scroll, where the bottom bar floats when shown. */
internal val BookInsetBottom = 96.dp

/** The top bar: the title, then the book's tools. Floats over the page, so it never moves the text. */
@Composable
internal fun BookTopBar(
    title: String,
    bookmarked: Boolean,
    tint: Color,
    onSearch: () -> Unit,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onNotebook: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().glassBar(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).padding(start = 16.dp),
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onSearch) { Icon(EzIcons.Search, "Find in book", tint = tint) }
        IconButton(onClick = onContents) { Icon(EzIcons.Menu, "Contents", tint = tint) }
        IconButton(onClick = onNotebook) { Icon(EzIcons.Book, "Notebook", tint = tint) }
        IconButton(onClick = onBookmark) {
            Icon(
                if (bookmarked) EzIcons.Bookmark else EzIcons.BookmarkOutline,
                if (bookmarked) "Remove bookmark" else "Bookmark this place",
                tint = tint,
            )
        }
        IconButton(onClick = onSettings) { Icon(EzIcons.TextT, "Reading settings", tint = tint) }
    }
}

/**
 * The bottom bar: the chapter, a scrubber with a tick at each chapter start, and a line that says
 * where the reader is. Dragging the scrubber only previews; the book moves when the finger lifts.
 * [onPrevious] and [onNext] are shown only in paged mode, where they turn one page.
 */
@Composable
internal fun BookBottomBar(
    chapterTitle: String,
    fraction: Float,
    ticks: List<Float>,
    footer: String,
    tint: Color,
    onScrub: (Float) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .floatingGlass()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            chapterTitle,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onPrevious != null) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp)) {
                    Icon(EzIcons.ChevronLeft, "Previous page", tint = tint)
                }
            }
            Box(Modifier.weight(1f)) {
                Slider(
                    value = dragging ?: fraction,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let(onScrub)
                        dragging = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = EzColors.Primary,
                        activeTrackColor = EzColors.Primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Chapter ticks, drawn over the track: where each chapter starts.
                Canvas(Modifier.matchParentSize().padding(horizontal = 10.dp)) {
                    val tickColor = tint.copy(alpha = 0.55f)
                    val mid = size.height / 2f
                    ticks.forEach { tick ->
                        val x = tick.coerceIn(0f, 1f) * size.width
                        drawLine(tickColor, Offset(x, mid - 7.dp.toPx()), Offset(x, mid + 7.dp.toPx()), strokeWidth = 2.dp.toPx())
                    }
                }
            }
            if (onNext != null) {
                IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                    Icon(EzIcons.ChevronRight, "Next page", tint = tint)
                }
            }
        }
        Text(
            footer,
            style = MaterialTheme.typography.labelMedium,
            color = tint.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shown after a link is followed: returns to where the reader was. */
@Composable
internal fun BookBackPill(tint: Color, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .floatingGlass()
            .clickable(onClick = onBack)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(EzIcons.Back, null, tint = tint, modifier = Modifier.size(16.dp))
        Text("Back", style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** The footer line: where the reader is in the chapter when paged, and how far through the book. */
internal fun bookFooter(pageInChapter: String?, percent: Int): String {
    val percentText = "$percent% of the book"
    return if (pageInChapter == null) percentText else "$pageInChapter · $percentText"
}
