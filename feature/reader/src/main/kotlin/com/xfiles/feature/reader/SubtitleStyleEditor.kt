package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.glassCard

/** Step of the timing control, in milliseconds. */
private const val DELAY_STEP_MS = 250L

/** Stateless: the style and timing come from [state]; every change goes out through [onEvent]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleStyleLevel(
    state: MediaPlayerUiState,
    onEvent: (MediaPlayerEvent) -> Unit,
    onBack: () -> Unit,
) {
    val style = state.subtitleStyle
    fun restyle(change: SubtitleStyle) = onEvent(MediaPlayerEvent.UpdateStyle(change))
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .glassCard(RoundedCornerShape(50), strength = 0.6f)
                    .clickable(role = Role.Button, onClickLabel = "Back to subtitle tracks") { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(EzIcons.Back, "Back", Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("Subtitle style", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                "Reset",
                style = MaterialTheme.typography.labelLarge,
                color = EzColors.Primary,
                modifier = Modifier
                    .clickable(role = Role.Button) {
                        restyle(SubtitleStyle())
                        if (state.canAdjustSubtitleTiming && state.subtitleDelayMs != 0L) onEvent(MediaPlayerEvent.SubtitleDelay(-state.subtitleDelayMs))
                    }
                    .padding(8.dp),
            )
        }
        SubtitlePreview(style)
        StyleGroup("Size") {
            SubtitleSize.entries.forEach { size ->
                OptionPill(size.label, style.size == size) { restyle(style.copy(size = size)) }
            }
        }
        StyleGroup("Colour") {
            SubtitleColor.entries.forEach { color ->
                OptionPill(color.label, style.color == color) { restyle(style.copy(color = color)) }
            }
        }
        StyleGroup("Background") {
            SubtitleBackground.entries.forEach { background ->
                OptionPill(background.label, style.background == background) { restyle(style.copy(background = background)) }
            }
        }
        StyleGroup("Outline") {
            SubtitleLook.entries.forEach { look ->
                OptionPill(look.label, style.look == look) { restyle(style.copy(look = look)) }
            }
        }
        StyleGroup("Position") {
            SubtitlePosition.entries.forEach { position ->
                OptionPill(position.label, style.position == position) { restyle(style.copy(position = position)) }
            }
        }
        if (state.canAdjustSubtitleTiming) {
            DelayControl(state.subtitleDelayMs) { onEvent(MediaPlayerEvent.SubtitleDelay(it)) }
        }
    }
}

/** The style applied to a sample line, over a picture-like backdrop so the contrast can be judged. */
@Composable
private fun SubtitlePreview(style: SubtitleStyle) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .glassCard(RoundedCornerShape(18.dp), strength = 0.5f)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF3B5B7A), Color(0xFF8C6A4A), Color(0xFF1C1C24))),
                RoundedCornerShape(18.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        SubtitleText(listOf("This is how subtitles look."), style, brightBehind = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleGroup(title: String, pills: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pills()
        }
    }
}

/** One option in a style group: filled when chosen, glass otherwise. */
@Composable
private fun OptionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val haptics = rememberMediaHaptics()
    Box(
        Modifier
            .height(36.dp)
            .glassCard(shape, strength = if (selected) 0.85f else 0.45f)
            .background(if (selected) EzColors.Primary.copy(alpha = 0.2f) else Color.Transparent, shape)
            .clickable(role = Role.RadioButton, onClickLabel = label) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** Shifts subtitle timing in quarter-second steps, with a reset. */
@Composable
private fun DelayControl(delayMs: Long, onShift: (Long) -> Unit) {
    val haptics = rememberMediaHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Timing", style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
        Row(
            Modifier.fillMaxWidth().glassCard(RoundedCornerShape(18.dp), strength = 0.45f).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OptionPill("Earlier", selected = false) {
                haptics.tick()
                onShift(-DELAY_STEP_MS)
            }
            Text(
                if (delayMs == 0L) "On time" else formatDelay(delayMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OptionPill("Later", selected = false) {
                haptics.tick()
                onShift(DELAY_STEP_MS)
            }
        }
        OptionPill("Reset timing", selected = delayMs == 0L) { onShift(-delayMs) }
    }
}

private fun formatDelay(ms: Long): String {
    val sign = if (ms > 0) "+" else "−"
    val seconds = kotlin.math.abs(ms) / 1000.0
    return "$sign${"%.2f".format(seconds).trimEnd('0').trimEnd('.')} s"
}
