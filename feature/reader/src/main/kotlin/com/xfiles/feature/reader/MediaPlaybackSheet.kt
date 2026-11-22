package com.xfiles.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import kotlin.math.roundToInt

/** Speed, media volume and player brightness, from the ⋮ menu. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MediaPlaybackSheet(
    speedIndex: Int,
    device: MediaDevice,
    onSpeed: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberMediaHaptics()
    var volume by remember { mutableFloatStateOf(device.volume()) }
    var brightness by remember { mutableFloatStateOf(device.brightness()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Playback settings", style = MaterialTheme.typography.titleLarge)
            Text("Speed", style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaSpeedSteps.forEachIndexed { index, speed ->
                    FilterChip(index == speedIndex, onClick = {
                        haptics.tick()
                        onSpeed(index)
                    }, label = { Text(speedLabel(speed)) })
                }
            }
            Text("Volume ${(volume * 100).roundToInt()}%")
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    device.setVolume(it)
                },
                onValueChangeFinished = { haptics.tick() },
                modifier = Modifier.semantics { contentDescription = "Media volume" },
            )
            Text("Brightness ${(brightness * 100).roundToInt()}%")
            Slider(
                value = brightness,
                onValueChange = {
                    brightness = it
                    device.setBrightness(it)
                },
                onValueChangeFinished = { haptics.tick() },
                modifier = Modifier.semantics { contentDescription = "Player brightness" },
            )
            Text(
                "Swipe left or right to seek. Swipe up or down on the left for brightness, on the right for volume. Double-tap a side to skip 10 seconds. Hold the picture for double speed.",
                color = EzColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
