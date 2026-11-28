package com.xfiles.feature.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzControlSheet
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzMotion
import com.xfiles.design.LocalReducedMotion
import com.xfiles.design.glassCard
import com.xfiles.feature.reader.domain.MediaSubtitleFile
import com.xfiles.feature.reader.domain.MediaTextTrack

/**
 * The subtitle sheet, in two levels. Level 1 picks the track: Off, the tracks in the video, files beside the video,
 * and Load from file. Level 2 (Style) is the look and timing, and the two slide in place of each other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaSubtitleSheet(
    state: MediaPlayerUiState,
    onEvent: (MediaPlayerEvent) -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val travel = with(LocalDensity.current) { 24.dp.roundToPx() }
    EzControlSheet(onDismiss) {
        AnimatedContent(
            targetState = state.subtitleLevel,
            transitionSpec = {
                val direction = if (targetState == SubtitleLevel.Style) forward else -forward
                EzMotion.horizontal(direction, travel, reduced)
            },
            label = "subtitleLevel",
        ) { level ->
            when (level) {
                SubtitleLevel.Tracks -> SubtitleTrackLevel(
                    state = state,
                    onEvent = onEvent,
                    onPickFile = onPickFile,
                    onStyle = { onEvent(MediaPlayerEvent.SetSubtitleLevel(SubtitleLevel.Style)) },
                )
                SubtitleLevel.Style -> SubtitleStyleLevel(
                    state = state,
                    onEvent = onEvent,
                    onBack = { onEvent(MediaPlayerEvent.SetSubtitleLevel(SubtitleLevel.Tracks)) },
                )
            }
        }
    }
}

@Composable
private fun SubtitleTrackLevel(
    state: MediaPlayerUiState,
    onEvent: (MediaPlayerEvent) -> Unit,
    onPickFile: () -> Unit,
    onStyle: () -> Unit,
) {
    val haptics = rememberMediaHaptics()
    LaunchedEffect(state.subtitleNotice) {
        if (state.subtitleNotice != null) haptics.reject()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Subtitles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            StylePill(onClick = onStyle)
        }
        MediaSectionLabel("In this video")
        MediaChoiceRow(
            title = "Off",
            detail = "No subtitles",
            selected = !state.subtitlesOn,
            onClick = { onEvent(MediaPlayerEvent.ChooseSubtitle(null)) },
        )
        state.textTracks.forEach { track ->
            MediaChoiceRow(
                title = track.title,
                detail = trackDetail(track),
                selected = track.selected,
                enabled = track.supported,
                warning = track.reason != null,
                onClick = { onEvent(MediaPlayerEvent.ChooseSubtitle(track)) },
            )
        }
        if (state.textTracks.isEmpty()) {
            Text(
                "No subtitles are built into this video.",
                style = MaterialTheme.typography.bodyMedium,
                color = EzColors.TextSecondary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (state.nearbySubtitles.isNotEmpty()) {
            MediaSectionLabel("Next to this video")
            state.nearbySubtitles.forEach { file ->
                MediaChoiceRow(
                    title = file.label(),
                    detail = "Subtitle file in this folder · ${file.extension()}",
                    selected = false,
                    cue = { it.confirm() },
                    onClick = { onEvent(MediaPlayerEvent.ChooseNearbySubtitle(file)) },
                )
            }
        }
        MediaSectionLabel("Add")
        LoadFileRow(state.subtitleLoading, onPickFile)
        if (state.subtitleNotice != null) {
            Text(
                state.subtitleNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** Opens the Style level: a glass pill with the Tune icon. */
@Composable
private fun StylePill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val haptics = rememberMediaHaptics()
    Row(
        Modifier
            .glassCard(shape, strength = 0.6f)
            .clickable {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MediaIcons.Tune, null, Modifier.size(18.dp), tint = EzColors.Primary)
        Spacer(Modifier.size(6.dp))
        Text("Style", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LoadFileRow(loading: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val haptics = rememberMediaHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .glassCard(shape, strength = 0.45f)
            .clickable {
                haptics.tick()
                onPick()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(EzIcons.Folder, null, Modifier.size(22.dp), tint = EzColors.Primary)
        Spacer(Modifier.size(12.dp))
        Text("Load from file… (.srt, .vtt, .ass)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.Unspecified, strokeWidth = 2.dp)
    }
}

private fun trackDetail(track: MediaTextTrack): String = track.reason
    ?: (listOf(track.format.label) + track.flags).joinToString(" · ")

private fun MediaSubtitleFile.label(): String = name.substringBeforeLast('.')

private fun MediaSubtitleFile.extension(): String =
    subtitleFormatLabel(name.substringAfterLast('.', "").lowercase())

private fun subtitleFormatLabel(ext: String): String = when (ext) {
    "vtt" -> "VTT"
    "ass", "ssa" -> "ASS"
    "ttml", "dfxp", "xml" -> "TTML"
    else -> "SRT"
}
