package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons

/** How many steps the seek bar has for the scrub ticks: one tick per 2 % of the length. */
private const val SCRUB_STEPS = 50

/**
 * Seek bar, then back 10 s, play/pause, forward 10 s, an optional repeat toggle and the time, over a fade to black.
 * [repeatOne] is null when the repeat toggle isn't offered (video).
 */
@Composable
internal fun MediaTransport(
    playing: Boolean,
    seekable: Boolean,
    position: Long,
    duration: Long,
    scrubFraction: Float?,
    repeatOne: Boolean?,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onForward: () -> Unit,
    onRepeat: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMediaHaptics()
    var lastStep by remember { mutableIntStateOf(-1) }
    val shown = scrubFraction ?: if (duration <= 0L) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
    val time = if (scrubFraction != null) (scrubFraction * duration).toLong() else position
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Slider(
            value = shown,
            onValueChange = { value ->
                // A tick each time the thumb crosses a step, so scrubbing has a texture under the finger.
                val step = (value * SCRUB_STEPS).toInt()
                if (step != lastStep) {
                    lastStep = step
                    haptics.tick()
                }
                onScrub(value)
            },
            onValueChangeFinished = {
                lastStep = -1
                onScrubFinished()
            },
            enabled = duration > 0L && seekable,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = EzColors.Primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.semantics { contentDescription = "Seek" },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(EzIcons.Rewind, "Back 10 seconds", onBack, enabled = seekable)
            RoundButton(if (playing) EzIcons.Pause else EzIcons.Play, if (playing) "Pause" else "Play", onToggle, large = true)
            RoundButton(EzIcons.Forward, "Forward 10 seconds", onForward, enabled = seekable)
            if (repeatOne != null) {
                RoundButton(
                    if (repeatOne) MediaIcons.RepeatOne else MediaIcons.Repeat,
                    if (repeatOne) "Repeat on" else "Repeat off",
                    onRepeat,
                    toggleOn = !repeatOne,
                )
            }
            Box(Modifier.weight(1f))
            Text(
                "${formatTime(time)} / ${formatTime(duration)}",
                color = Color.White,
                fontSize = 14.sp,
            )
        }
    }
}
