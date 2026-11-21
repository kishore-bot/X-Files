package com.xfiles.feature.reader

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import com.xfiles.core.documents.FileEntry
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaScreen(file: FileEntry, video: Boolean) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    var player by remember(file.uri) { mutableStateOf<ExoPlayer?>(null) }
    // Survives rotation and process death, so the video picks up where it was instead of restarting.
    var resumeAt by rememberSaveable(file.uri) { mutableLongStateOf(0L) }
    var resumePlaying by rememberSaveable(file.uri) { mutableStateOf(false) }

    DisposableEffect(file.uri, lifecycle) {
        val activePlayer = ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
        activePlayer.setAudioAttributes(AudioAttributes.DEFAULT, true)
        activePlayer.setHandleAudioBecomingNoisy(true)
        activePlayer.setMediaItem(MediaItem.fromUri(file.uri), resumeAt)
        activePlayer.playWhenReady = resumePlaying
        activePlayer.prepare()
        player = activePlayer
        fun keepPlace() {
            resumeAt = activePlayer.currentPosition.coerceAtLeast(0)
            // Rotation keeps playing; leaving the app comes back paused.
            resumePlaying = activePlayer.playWhenReady && activity?.isChangingConfigurations == true
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                keepPlace()
                if (activity?.isChangingConfigurations != true) activePlayer.pause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            keepPlace()
            activePlayer.release()
            player = null
        }
    }

    val activePlayer = player
    if (activePlayer == null) {
        CircularProgressIndicator()
    } else {
        PlayerContent(activePlayer, video, file)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerContent(player: ExoPlayer, video: Boolean, file: FileEntry) {
    fun readState() = PlaybackUiState(
        player.playbackState,
        player.isPlaying,
        player.currentPosition.coerceAtLeast(0),
        player.duration.coerceAtLeast(0),
        player.playerError != null,
    )
    var playback by remember(player) { mutableStateOf(readState()) }
    var dragging by remember(player) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scrub = remember(player) { SliderState() }
    var resumeAfterScrub by remember(player) { mutableStateOf(false) }
    val context = LocalContext.current
    val styleStore = remember { SubtitleStyleStore(context.applicationContext) }
    var subtitleStyle by remember { mutableStateOf(styleStore.load()) }
    var subtitleSheet by remember { mutableStateOf(false) }
    var tracks by remember(player) { mutableStateOf(player.subtitleTracks()) }
    val cues = rememberCues(player)
    val brightBehind = rememberBrightBehindSubtitles(
        file.uri,
        player,
        active = video && cues.isNotEmpty() && subtitleStyle.color == SubtitleColor.Auto && subtitleStyle.background == SubtitleBackground.None,
    )
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playback = readState()
                if (events.contains(Player.EVENT_TRACKS_CHANGED)) tracks = player.subtitleTracks()
            }
        }
        player.addListener(listener)
        playback = readState()
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, playback.playing) {
        if (playback.playing) {
            while (true) {
                delay(250)
                playback = readState()
            }
        }
    }
    if (playback.error) {
        ExternalOpen(file, message = "This media could not be played in Xfiles.")
        return
    }
    val total = playback.duration

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (video) {
                ContentFrame(
                    player = player,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(player) {
                            detectTapGestures(
                                // One tap only plays or pauses; seeking needs a deliberate double tap.
                                onTap = {
                                    if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                                        player.pause()
                                    } else {
                                        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                                        player.play()
                                    }
                                },
                                onDoubleTap = { offset ->
                                    if (player.isCurrentMediaItemSeekable) {
                                        if (offset.x < size.width / 2f) player.seekBack() else player.seekForward()
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                },
                            )
                        }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Back 10 seconds") { player.seekBack(); true },
                                CustomAccessibilityAction("Forward 10 seconds") { player.seekForward(); true },
                            )
                        },
                )
            } else {
                Icon(EzIcons.Music, null, Modifier.size(104.dp), tint = EzColors.Primary)
            }
            if (video) {
                SubtitleText(cues, subtitleStyle, brightBehind, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 16.dp, end = 16.dp))
            }
            if (dragging && total > 0L) {
                // The picture follows the finger; this box says where it is going.
                val target = (scrub.value * total).toLong()
                val delta = (target - playback.position) / 1000
                Column(
                    Modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${formatTime(target)} / ${formatTime(total)}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    if (delta != 0L) Text((if (delta > 0) "+" else "−") + formatTime(kotlin.math.abs(delta) * 1000), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                }
            } else if (playback.status == Player.STATE_BUFFERING) {
                CircularProgressIndicator()
            }
        }
        Column(Modifier.fillMaxWidth().glassBar().padding(horizontal = 16.dp)) {
            SideEffect {
                if (!dragging) scrub.value = if (total == 0L) 0f else (playback.position.toFloat() / total).coerceIn(0f, 1f)
            }
            Slider(
                state = scrub,
                enabled = total > 0L && player.isCurrentMediaItemSeekable,
                onValueChange = { value ->
                    if (!dragging) {
                        // Scrubbing mode: fast, frame-accurate-enough seeks while the finger moves.
                        dragging = true
                        resumeAfterScrub = player.playWhenReady
                        player.pause()
                        player.isScrubbingModeEnabled = true
                    }
                    scrub.value = value
                    if (video) player.seekTo((value * total).toLong())
                },
                onValueChangeFinished = {
                    player.seekTo((scrub.value * total).toLong())
                    player.isScrubbingModeEnabled = false
                    if (resumeAfterScrub) player.play()
                    playback = readState()
                    dragging = false
                },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(if (dragging) (scrub.value * total).toLong() else playback.position))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekBack() }, enabled = player.isCurrentMediaItemSeekable) {
                        Icon(EzIcons.Rewind, "Seek back")
                    }
                    IconButton(onClick = {
                        if (player.playWhenReady && playback.status != Player.STATE_ENDED) {
                            player.pause()
                        } else {
                            if (playback.status == Player.STATE_ENDED) player.seekToDefaultPosition()
                            player.play()
                        }
                    }) {
                        val playing = player.playWhenReady && playback.status != Player.STATE_ENDED
                        Icon(if (playing) EzIcons.Pause else EzIcons.Play, if (playing) "Pause" else "Play")
                    }
                    IconButton(onClick = { player.seekForward() }, enabled = player.isCurrentMediaItemSeekable) {
                        Icon(EzIcons.Forward, "Seek forward")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (video) {
                        TextButton(onClick = { subtitleSheet = true }) {
                            Text("CC", fontWeight = if (tracks.any { it.selected }) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    Text(formatTime(total))
                }
            }
        }
    }
    if (subtitleSheet) {
        SubtitleSheet(
            tracks = tracks,
            style = subtitleStyle,
            onTrack = { player.showSubtitles(it) },
            onLoad = { uri -> loadSubtitles(context, player, file, uri) },
            onStyle = {
                subtitleStyle = it
                styleStore.save(it)
            },
            onDismiss = { subtitleSheet = false },
        )
    }
}

/** Adds a subtitle file to the playing video, keeping its place, and turns it on. */
private fun loadSubtitles(context: android.content.Context, player: ExoPlayer, file: FileEntry, subtitles: Uri) {
    val name = context.contentResolver.query(subtitles, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: subtitles.lastPathSegment.orEmpty()
    val mime = subtitleMime(name) ?: MimeTypes.APPLICATION_SUBRIP
    val position = player.currentPosition
    val playing = player.playWhenReady
    val item = MediaItem.Builder()
        .setUri(file.uri)
        .setSubtitleConfigurations(
            listOf(
                MediaItem.SubtitleConfiguration.Builder(subtitles)
                    .setMimeType(mime)
                    .setLabel(name.substringBeforeLast('.'))
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build(),
            ),
        )
        .build()
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .build()
    player.setMediaItem(item, position)
    player.playWhenReady = playing
    player.prepare()
}

private data class PlaybackUiState(
    val status: Int,
    val playing: Boolean,
    val position: Long,
    val duration: Long,
    val error: Boolean,
)

private const val SKIP_MS = 10_000L

private fun formatTime(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$remainder" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$remainder"
}
