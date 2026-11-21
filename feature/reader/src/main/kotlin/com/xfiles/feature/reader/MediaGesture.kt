package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** Brightness / volume pill on one side of the picture: icon, level bar and percentage. */
@Composable
internal fun GestureLevelPill(kind: MediaDrag, level: Float, modifier: Modifier = Modifier) {
    val label = if (kind == MediaDrag.Volume) "Volume" else "Brightness"
    Column(
        modifier
            .width(48.dp)
            .height(190.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (kind == MediaDrag.Volume) MediaIcons.Volume else MediaIcons.Brightness,
            label,
            Modifier.size(20.dp),
            tint = Color.White,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.width(4.dp).weight(1f).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight(level.coerceIn(0f, 1f)).background(Color.White))
        }
        Spacer(Modifier.height(10.dp))
        Text("${(level.coerceIn(0f, 1f) * 100).roundToInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * What the current drag shows over the picture. Reads [drag] itself, so a finger moving does not
 * recompose the whole player, only this layer.
 */
@Composable
internal fun BoxScope.GestureOverlay(drag: State<MediaDragState?>, total: Long, position: Long) {
    val gesture = drag.value ?: return
    when (gesture.kind) {
        MediaDrag.Brightness -> GestureLevelPill(gesture.kind, gesture.level, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        MediaDrag.Volume -> GestureLevelPill(gesture.kind, gesture.level, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
        MediaDrag.Seek -> if (total > 0L) ScrubBubble(gesture.positionMs, total, position)
    }
}

/** "1:23 / 4:56" with the change from the current position, shown while seeking. */
@Composable
internal fun ScrubBubble(target: Long, total: Long, position: Long) {
    val delta = (target - position) / 1000
    Column(
        Modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${formatTime(target)} / ${formatTime(total)}", color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (delta != 0L) {
            Text(
                (if (delta > 0) "+" else "−") + formatTime(abs(delta) * 1000),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal object MediaIcons {
    val Brightness: ImageVector = mediaIcon(
        "M20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm0-10c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z",
    )
    val Volume: ImageVector = mediaIcon(
        "M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4zM14 3.23v2.06a7 7 0 0 1 0 13.42v2.06A9 9 0 0 0 14 3.23z",
    )
}

private fun mediaIcon(path: String): ImageVector =
    ImageVector.Builder(name = "MediaIcon", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
