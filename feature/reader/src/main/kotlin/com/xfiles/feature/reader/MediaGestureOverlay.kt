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
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

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
internal fun BoxScope.GestureOverlay(drag: State<MediaDragState?>, total: Long) {
    val gesture = drag.value ?: return
    when (gesture.kind) {
        MediaDrag.Brightness -> GestureLevelPill(gesture.kind, gesture.level, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        MediaDrag.Volume -> GestureLevelPill(gesture.kind, gesture.level, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
        MediaDrag.Seek -> if (total > 0L) ScrubBubble(gesture.positionMs, total, gesture.startMs)
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
