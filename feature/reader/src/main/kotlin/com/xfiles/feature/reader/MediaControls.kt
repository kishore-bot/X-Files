package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Playback speeds offered in Playback settings. */
internal val MediaSpeedSteps: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Index of 1x in [MediaSpeedSteps]. */
internal const val MediaSpeedDefault = 2

internal fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "x"

/** How the picture fills the screen, cycled from the menu. */
internal enum class VideoScale(val label: String, val scale: ContentScale) {
    Fit("Fit", ContentScale.Fit),
    Fill("Fill (crop)", ContentScale.Crop),
    Stretch("Stretch", ContentScale.FillBounds),
    ;
    fun next(): VideoScale = entries[(ordinal + 1) % entries.size]
}

/**
 * A round player button: white and large for play/pause, translucent otherwise. [toggleOn] makes a switch
 * (repeat) give the toggle cue; without it the button gives the ordinary tick.
 */
@Composable
internal fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    large: Boolean = false,
    enabled: Boolean = true,
    toggleOn: Boolean? = null,
) {
    val haptics = rememberMediaHaptics()
    Box(
        Modifier
            .size(if (large) 56.dp else 48.dp)
            .clip(CircleShape)
            .background(if (large) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
                if (toggleOn != null) haptics.toggle(toggleOn) else haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = (if (large) Color.Black else Color.White).copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(if (large) 30.dp else 22.dp),
        )
    }
}

/** "1:23" or "1:02:03". */
internal fun formatTime(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$remainder" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$remainder"
}
