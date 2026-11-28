package com.xfiles.feature.reader

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.xfiles.design.EzControlSheet
import com.xfiles.design.EzColors
import kotlin.math.roundToInt

/** Speed, media volume and player brightness, from the ⋮ menu. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MediaPlaybackSheet(
    speedIndex: Int,
    device: MediaDevice,
    output: MediaOutput,
    controls: PlaybackControls,
    video: Boolean,
    onSpeed: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberMediaHaptics()
    var volume by remember { mutableFloatStateOf(device.volume()) }
    var brightness by remember { mutableFloatStateOf(device.brightness()) }
    EzControlSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Playback settings", style = MaterialTheme.typography.titleLarge)
            Text("Speed", style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaSpeedSteps.forEachIndexed { index, speed ->
                    FilterChip(index == speedIndex, onClick = {
                        haptics.tick()
                        onSpeed(index)
                    }, enabled = !output.usbDirectRequested, label = { Text(speedLabel(speed)) })
                }
            }
            Text("Volume ${(volume * 100).roundToInt()}%")
            Slider(
                value = volume,
                enabled = !output.usbDirectRequested,
                onValueChange = {
                    volume = it
                    device.setVolume(it)
                },
                onValueChangeFinished = { haptics.tick() },
                modifier = Modifier.semantics { contentDescription = "Media volume" },
            )
            if (video) {
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
                if (controls.canBoostPicture) {
                    FilterChip(controls.pictureBoost, controls.togglePictureBoost, label = { Text("SDR picture boost") })
                }
            }
            if (output.canBoost || output.boosted) {
                FilterChip(output.boosted, controls.toggleAudioBoost, label = { Text("Audio boost +3 dB") })
            }
            if (controls.canSwitchDecoder && !output.usbDirectRequested) {
                TextButton(onClick = { onDismiss(); controls.switchDecoder() }) {
                    Text(if (controls.softwarePreferred) "Use automatic audio decoding" else "Prefer software audio")
                }
            }
            if (output.autoLosslessAvailable) {
                FilterChip(output.autoLossless, { controls.setAutoLossless(!output.autoLossless) },
                    label = { Text("Auto lossless for USB") })
                output.usbLosslessDevice?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                output.usbLosslessStatus?.let { Text(it, color = EzColors.TextSecondary) }
            }
            output.spatial?.let { Text("Spatial audio · $it") }
            if (output.headTrackingAvailable) Text("Head tracking available")
            output.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (video) Text(
                "Swipe left or right to seek. Swipe up or down on the left for brightness, on the right for volume. Double-tap a side to skip 10 seconds. Hold the picture for double speed.",
                color = EzColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
