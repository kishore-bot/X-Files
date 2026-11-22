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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.floatingGlass
import com.xfiles.design.rememberHaptics

/** Space above a page or the first row of the scroll, where the top bar floats when shown. */
internal val BookInsetTop = 72.dp

/** Space below a page or the last row of the scroll, where the bottom bar floats when shown. */
internal val BookInsetBottom = 120.dp

/**
 * The top bar, as the EzFiles reader's: one floating glass menu with Contents and Aa on the left,
 * the book's title, then find, bookmark and hide. It is the reader's only bar; it floats over the
 * page and never moves the text.
 */
@Composable
internal fun BookTopBar(
    title: String,
    bookmarked: Boolean,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onBookmark: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    onFily: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(10.dp)
            .floatingGlass(EzShapes.Menu, 0.85f)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeButton(EzIcons.Back, "Back to library", onClose)
        ChromeButton(EzIcons.ListLines, "Contents", onContents)
        ChromeButton(EzIcons.TextT, "Reading settings", onSettings, label = "Aa")
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = EzColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        ChromeButton(EzIcons.Search, "Find in book", onSearch)
        if (onFily != null) ChromeButton(EzIcons.Mic, "Ask Fily", onFily)
        ChromeButton(
            if (bookmarked) EzIcons.Bookmark else EzIcons.BookmarkOutline,
            if (bookmarked) "Remove bookmark" else "Bookmark this page",
            onBookmark,
        )
        ChromeButton(EzIcons.Close, "Hide menu", onHide)
    }
}

/** One item of the top bar: an icon, with its label beside it when it has one. */
@Composable
private fun ChromeButton(icon: ImageVector, description: String, onClick: () -> Unit, label: String? = null) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = description) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = if (label == null) description else null, tint = EzColors.Text, modifier = Modifier.size(22.dp))
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = EzColors.Text, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * The bottom bar, as the EzFiles reader's: the chapter, a scrubber with a tick at each chapter start,
 * and a line that says where the reader is. Dragging only previews; the book moves when the finger
 * lifts. [onPrevious] and [onNext] are shown only in paged mode, where they turn one page.
 */
@Composable
internal fun BookBottomBar(
    chapterTitle: String,
    fraction: Float,
    ticks: List<Float>,
    footer: String,
    onScrub: (Float) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val tickColor = EzColors.Text.copy(alpha = 0.55f)
    Column(
        modifier
            .fillMaxWidth()
            .padding(10.dp)
            .floatingGlass(EzShapes.Menu, 0.85f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(chapterTitle, style = MaterialTheme.typography.titleSmall, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onPrevious != null) ChromeChevron(EzIcons.ChevronLeft, "Previous page", onPrevious)
            Box(Modifier.weight(1f)) {
                Slider(
                    value = dragging ?: fraction,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let(onScrub)
                        dragging = null
                    },
                    colors = SliderDefaults.colors(thumbColor = EzColors.Primary, activeTrackColor = EzColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Chapter ticks, drawn over the track: where each chapter starts.
                Canvas(Modifier.matchParentSize().padding(horizontal = 10.dp)) {
                    val mid = size.height / 2f
                    ticks.forEach { tick ->
                        val x = tick.coerceIn(0f, 1f) * size.width
                        drawLine(tickColor, Offset(x, mid - 7.dp.toPx()), Offset(x, mid + 7.dp.toPx()), strokeWidth = 2.dp.toPx())
                    }
                }
            }
            if (onNext != null) ChromeChevron(EzIcons.ChevronRight, "Next page", onNext)
        }
        Text(footer, style = MaterialTheme.typography.labelMedium, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round touch target for a page turn, at either end of the scrubber. */
@Composable
private fun ChromeChevron(icon: ImageVector, description: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description) {
                haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = EzColors.Text, modifier = Modifier.size(24.dp))
    }
}

/** Shown after a link is followed: returns to where the reader was. */
@Composable
internal fun BookBackPill(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .floatingGlass(EzShapes.Pill, 0.85f)
            .clickable(role = Role.Button, onClickLabel = "Back to where you were") {
                haptics.tick()
                onBack()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(EzIcons.Back, null, tint = EzColors.Text, modifier = Modifier.size(16.dp))
        Text("Back", style = MaterialTheme.typography.labelLarge, color = EzColors.Text)
    }
}

/** The chapter, in the page's own colours, above the text while the bars are away. */
@Composable
internal fun BookChapterLine(text: String, tint: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = tint,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 16.dp),
    )
}

/** The page's footer while the bars are away: pages left in the chapter, and the share of the book read. */
@Composable
internal fun BookPageFooter(left: String, right: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(left, color = tint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(right, color = tint, fontSize = 12.sp)
    }
}

/** The footer line of the bottom bar: where the reader is in the chapter when paged, and how far through the book. */
internal fun bookFooter(pageInChapter: String?, percent: Int): String {
    val percentText = "$percent% of the book"
    return if (pageInChapter == null) percentText else "$pageInChapter · $percentText"
}
