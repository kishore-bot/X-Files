package com.xfiles.feature.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlin.math.abs

/** The one-finger gesture that owns the current drag. */
internal enum class MediaDrag { Seek, Brightness, Volume }

/**
 * One drag as an immutable value. Every finger movement produces a new copy, computed from the
 * total movement since touch-down, so a drag never drifts.
 */
internal data class MediaDragState(
    val kind: MediaDrag,
    /** Brightness or volume when the drag started, 0..1. */
    val startLevel: Float = 0f,
    /** Brightness or volume now, 0..1. */
    val level: Float = 0f,
    /** Seek: the position when the drag started. */
    val startMs: Long = 0L,
    /** Seek: the position the drag points at; applied on release. */
    val positionMs: Long = 0L,
)

/** Pure gesture rules, kept free of Android types so they can be unit-tested. */
internal object MediaGestures {
    /** Touches starting this close to a screen edge belong to the system back gesture. */
    const val EDGE_GUARD_DP = 24f

    /** Movement before a touch counts as a drag; below it the tap detectors own the touch. */
    const val SLOP_DP = 16f

    /** One full screen width of horizontal drag covers this much media time, clamped to the duration. */
    const val SEEK_SPAN_MS = 90_000L

    fun inEdgeGuard(x: Float, y: Float, width: Float, height: Float, guardPx: Float): Boolean =
        x < guardPx || y < guardPx || x > width - guardPx || y > height - guardPx

    /** Horizontal wins when it dominates; otherwise the side the touch started on picks brightness (left) or volume (right). */
    fun kindFor(totalX: Float, totalY: Float, originX: Float, width: Float): MediaDrag = when {
        abs(totalX) >= abs(totalY) -> MediaDrag.Seek
        originX < width / 2f -> MediaDrag.Brightness
        else -> MediaDrag.Volume
    }

    /** Up raises the level; one full screen height sweeps the whole range. */
    fun levelFor(start: Float, totalY: Float, height: Float): Float =
        (start - totalY / height.coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun seekFor(startMs: Long, totalX: Float, width: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return startMs
        val span = minOf(durationMs, SEEK_SPAN_MS)
        return (startMs + (totalX / width.coerceAtLeast(1f) * span).toLong()).coerceIn(0L, durationMs)
    }

    /** True the moment a level reaches its minimum or maximum; that is when the haptic tick fires. */
    fun hitsBound(before: Float, after: Float): Boolean = before != after && (after == 0f || after == 1f)
}

/**
 * Taps on the picture: a tap shows or hides the controls, a double tap on a side skips 10 seconds, and a long
 * press runs at double speed until the finger lifts.
 */
@Composable
internal fun Modifier.mediaTapGestures(
    player: Player,
    haptics: MediaHaptics,
    onToggleControls: () -> Unit,
    onSkip: (forward: Boolean) -> Unit,
    onBoost: (on: Boolean) -> Unit,
): Modifier {
    val toggle by rememberUpdatedState(onToggleControls)
    val skip by rememberUpdatedState(onSkip)
    val boost by rememberUpdatedState(onBoost)
    return this.pointerInput(player) {
        var boosted = false
        detectTapGestures(
            onTap = { toggle() },
            onDoubleTap = { offset ->
                if (player.isCurrentMediaItemSeekable) {
                    skip(offset.x >= size.width / 2f)
                    haptics.segment()
                }
            },
            onLongPress = {
                haptics.longPress()
                boosted = true
                boost(true)
            },
            onPress = {
                tryAwaitRelease()
                if (boosted) {
                    boosted = false
                    boost(false)
                }
            },
        )
    }
}

/**
 * One finger over the picture: a horizontal drag seeks, a vertical one on the left sets brightness, on the right
 * volume. The gesture reports each step through [onDrag] (null when it ends) and seeks once, on release, through
 * [onSeek]. Touches that start at the screen edge belong to the system and are ignored.
 */
@Composable
internal fun Modifier.mediaDragGestures(
    player: Player,
    device: MediaDevice,
    haptics: MediaHaptics,
    enabled: Boolean,
    onDrag: (MediaDragState?) -> Unit,
    onSeek: (Long) -> Unit,
): Modifier {
    val density = LocalDensity.current
    val slopPx = with(density) { MediaGestures.SLOP_DP.dp.toPx() }
    val edgePx = with(density) { MediaGestures.EDGE_GUARD_DP.dp.toPx() }
    val drag by rememberUpdatedState(onDrag)
    val seek by rememberUpdatedState(onSeek)
    return this.pointerInput(player, device, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            if (MediaGestures.inEdgeGuard(down.position.x, down.position.y, width, height, edgePx)) {
                return@awaitEachGesture
            }
            var moved = Offset.Zero
            var current: MediaDragState? = null
            var released = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    released = true
                    break
                }
                if (event.changes.count { it.pressed } > 1) break
                moved += change.position - change.previousPosition
                if (current == null) {
                    if (moved.getDistance() < slopPx) continue
                    current = when (MediaGestures.kindFor(moved.x, moved.y, down.position.x, width)) {
                        MediaDrag.Seek -> MediaDragState(
                            MediaDrag.Seek,
                            startMs = player.currentPosition,
                            positionMs = player.currentPosition,
                        )
                        MediaDrag.Brightness -> device.brightness().let {
                            MediaDragState(MediaDrag.Brightness, startLevel = it, level = it)
                        }
                        MediaDrag.Volume -> device.volume().let {
                            MediaDragState(MediaDrag.Volume, startLevel = it, level = it)
                        }
                    }
                    haptics.gestureStart()
                }
                val state: MediaDragState = current ?: continue
                change.consume()
                val next = when (state.kind) {
                    MediaDrag.Seek -> state.copy(
                        positionMs = MediaGestures.seekFor(state.startMs, moved.x, width, player.duration),
                    )
                    MediaDrag.Brightness, MediaDrag.Volume -> state.copy(
                        level = MediaGestures.levelFor(state.startLevel, moved.y, height),
                    )
                }
                when (next.kind) {
                    MediaDrag.Brightness -> device.setBrightness(next.level)
                    MediaDrag.Volume -> device.setVolume(next.level)
                    MediaDrag.Seek -> Unit
                }
                if (next.kind != MediaDrag.Seek && MediaGestures.hitsBound(state.level, next.level)) {
                    haptics.segment()
                }
                current = next
                drag(next)
            }
            // Seeking applies once, on release; a cancelled touch seeks nowhere.
            current?.let {
                haptics.gestureEnd()
                if (released && it.kind == MediaDrag.Seek) seek(it.positionMs)
            }
            drag(null)
        }
    }
}
