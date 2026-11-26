package com.xfiles.feature.reader

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.FileEntry
import kotlinx.coroutines.CancellationException

/**
 * Swipe between the pictures of [session]. Only the settled page can zoom in or pan; while it is zoomed, or while
 * two fingers are down, paging is off (the same rule as EzFiles' photo viewer). The pages either side are composed
 * (beyondViewportPageCount = 1), so their screen-sized decodes start before the swipe lands.
 *
 * [load] decodes page [index] to fit a longest edge of the given pixel size; it is suspending and cancellable.
 * [animate] yields the animation of a GIF/WebP/AVIF page, and is asked only for the settled page.
 * [rotationOf] gives each picture's own quarter turns, so turning one picture does not turn the others.
 * [onSettle] receives the index a swipe landed on, so the controller can move the reader and the decode window.
 */
@Composable
internal fun GalleryPager(
    session: GallerySession,
    seed: Bitmap?,
    seedUri: String?,
    rotationOf: (String) -> Int,
    onSettle: (Int) -> Unit,
    load: suspend (index: Int, maxEdge: Int) -> Bitmap?,
    animate: suspend (index: Int, maxEdge: Int) -> AnimatedImageDrawable?,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = session.index, pageCount = { session.size })
    var zoomed by remember { mutableStateOf(false) }
    var multiTouch by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != session.index) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSettle(pager.settledPage)
        }
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Passive observer in the Initial pass: sees every finger before the pager or the image take the gesture.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        multiTouch = event.changes.count { it.pressed } > 1
                    }
                }
            },
    ) {
        val maxEdge = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { session.entries[it].uri.toString() },
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed && !multiTouch,
        ) { page ->
            GalleryPage(
                entry = session.entries[page],
                page = page,
                settled = page == pager.settledPage,
                seed = seed,
                seedUri = seedUri,
                maxEdge = maxEdge,
                rotation = rotationOf(session.entries[page].uri.toString()),
                load = load,
                animate = animate,
                onZoomChanged = { zoomed = it },
            )
        }
    }
}

@Composable
private fun GalleryPage(
    entry: FileEntry,
    page: Int,
    settled: Boolean,
    seed: Bitmap?,
    seedUri: String?,
    maxEdge: Int,
    rotation: Int,
    load: suspend (Int, Int) -> Bitmap?,
    animate: suspend (Int, Int) -> AnimatedImageDrawable?,
    onZoomChanged: (Boolean) -> Unit,
) {
    val seeded = seed?.takeIf { seedUri == entry.uri.toString() }
    val pageState by produceState<GalleryPageState>(
        initialValue = if (seeded != null) GalleryPageState.Shown(seeded) else GalleryPageState.Loading,
        page,
        maxEdge,
    ) {
        if (value is GalleryPageState.Shown) return@produceState
        value = try {
            load(page, maxEdge)?.let { GalleryPageState.Shown(it) } ?: GalleryPageState.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GalleryPageState.Failed
        }
    }
    // Only the settled page decodes an animation; leaving the page drops it, which frees the decoder.
    val animation by produceState<AnimatedImageDrawable?>(null, page, maxEdge, settled) {
        value = if (!settled) null else try {
            animate(page, maxEdge)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    // Opens lazily, so this costs nothing until the settled page is zoomed past the sharp threshold.
    val regions = rememberPictureRegions(entry.uri)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val shown = pageState) {
            GalleryPageState.Loading -> CircularProgressIndicator()
            GalleryPageState.Failed -> Text(
                "This photo is unavailable or this device can't display it.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
            is GalleryPageState.Shown ->
                // Only the settled page reads and writes its durable zoom and pan.
                key(settled) {
                    ReaderImage(
                        bitmap = shown.bitmap,
                        description = entry.name,
                        rotation = rotation,
                        modifier = Modifier.fillMaxSize(),
                        onZoomChanged = if (settled) onZoomChanged else NoZoom,
                        regions = if (settled) regions else null,
                        animated = animation,
                        animating = settled,
                        pictureKey = entry.uri.toString().takeIf { settled },
                    )
                }
        }
    }
}

private val NoZoom: (Boolean) -> Unit = {}

private sealed interface GalleryPageState {
    data object Loading : GalleryPageState
    data object Failed : GalleryPageState
    class Shown(val bitmap: Bitmap) : GalleryPageState
}
