package com.xfiles.feature.reader

import androidx.compose.ui.layout.boundsInWindow
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import com.xfiles.core.documents.FileEntry
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.LocalHapticsEnabled
import com.xfiles.design.glassBar
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaScreen(file: FileEntry, video: Boolean) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val pip = LocalMediaPip.current
    var player by remember(file.uri) { mutableStateOf<ExoPlayer?>(null) }
    // Survives rotation and process death, so the video picks up where it was instead of restarting.
    var resumeAt by rememberSaveable(file.uri) { mutableLongStateOf(0L) }
    var resumePlaying by rememberSaveable(file.uri) { mutableStateOf(false) }

    // The orientation the reader had comes back when the player closes.
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation
        onDispose {
            if (activity != null) activity.requestedOrientation = original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

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
                // Picture-in-Picture keeps the picture playing: the window shrinks, it is not left.
                if (activity?.isChangingConfigurations != true && !pip.isInPictureInPicture.value) activePlayer.pause()
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
    val context = LocalContext.current
    val activity = LocalActivity.current
    val pip = LocalMediaPip.current
    val inPip by pip.isInPictureInPicture.collectAsStateWithLifecycle()
    fun readState() = PlaybackUiState(
        player.playbackState,
        player.isPlaying,
        player.currentPosition.coerceAtLeast(0),
        player.duration.coerceAtLeast(0),
        player.playerError != null,
        player.videoSize.displayAspect(),
    )
    var playback by remember(player) { mutableStateOf(readState()) }
    // The slider's own drag (live scrubbing) and the gesture drag over the picture are separate.
    var scrubbing by remember(player) { mutableStateOf(false) }
    val gesture = remember(player) { mutableStateOf<MediaDragState?>(null) }
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    val density = LocalDensity.current
    val slopPx = with(density) { MediaGestures.SLOP_DP.dp.toPx() }
    val edgePx = with(density) { MediaGestures.EDGE_GUARD_DP.dp.toPx() }
    val device = rememberMediaDevice()
    val scrub = remember(player) { SliderState() }
    var resumeAfterScrub by remember(player) { mutableStateOf(false) }
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
    var speedIndex by rememberSaveable(file.uri) { mutableIntStateOf(MediaSpeedDefault) }
    // -1 until the first frame says whether the video is landscape; the default is then chosen once.
    var orientationIndex by rememberSaveable(file.uri) { mutableIntStateOf(-1) }
    val orientation = MediaOrientation.entries.getOrElse(orientationIndex) { MediaOrientation.Auto }
    var videoBounds by remember(player) { mutableStateOf<ComposeRect?>(null) }

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
    LaunchedEffect(player, speedIndex) { player.setPlaybackSpeed(MediaSpeedSteps[speedIndex]) }
    LaunchedEffect(playback.aspect) {
        if (orientationIndex < 0 && playback.aspect > 0f) {
            orientationIndex = (if (playback.aspect > 1f) MediaOrientation.Landscape else MediaOrientation.Auto).ordinal
        }
    }
    LaunchedEffect(orientation, video) {
        if (video && activity != null) activity.requestedOrientation = orientation.requested
    }
    // The PiP window's shape, actions and auto-enter follow the playing video.
    LaunchedEffect(activity, video, playback.playing, playback.aspect, videoBounds) {
        if (video && activity != null) {
            pip.update(activity, playerPipSpec(context, playback.aspect, videoBounds, playback.playing))
        }
    }
    DisposableEffect(player, activity) {
        onDispose { if (activity != null) pip.clear(activity) }
    }
    if (video) PipRemoteControls(player)
    if (playback.error) {
        ExternalOpen(file, message = "This media could not be played in Xfiles.")
        return
    }
    val total = playback.duration

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (inPip) 0.dp else 8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { videoBounds = it.boundsInWindow() },
            contentAlignment = Alignment.Center,
        ) {
            if (video) {
                ContentFrame(
                    player = player,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(player) {
                            detectTapGestures(
                                // One tap only plays or pauses; seeking needs a deliberate double tap.
                                onTap = { player.togglePlayback() },
                                onDoubleTap = { offset ->
                                    if (player.isCurrentMediaItemSeekable) {
                                        if (offset.x < size.width / 2f) player.seekBack() else player.seekForward()
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                },
                            )
                        }
                        .pointerInput(player, device, inPip) {
                            if (inPip) return@pointerInput
                            // One finger only: a horizontal drag seeks, a vertical one on the left sets brightness,
                            // on the right volume. The tap detector above keeps taps; this one ignores edge starts.
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
                                    if (next.kind != MediaDrag.Seek && hapticsOn &&
                                        MediaGestures.hitsBound(state.level, next.level)
                                    ) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                    current = next
                                    gesture.value = next
                                }
                                // Seeking applies once, on release; a cancelled touch seeks nowhere.
                                current?.takeIf { released && it.kind == MediaDrag.Seek }?.let { player.seekTo(it.positionMs) }
                                gesture.value = null
                            }
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
            if (video && !inPip) {
                SubtitleText(cues, subtitleStyle, brightBehind, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 16.dp, end = 16.dp))
            }
            if (video && !inPip) {
                VideoOverlayControls(
                    speed = MediaSpeedSteps[speedIndex],
                    orientation = orientation,
                    onSpeed = { speedIndex = (speedIndex + 1) % MediaSpeedSteps.size },
                    onOrientation = { orientationIndex = (orientation.ordinal + 1) % MediaOrientation.entries.size },
                    onPictureInPicture = {
                        if (activity != null) pip.enter(activity, playerPipSpec(context, playback.aspect, videoBounds, playback.playing))
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
            GestureOverlay(gesture, total, playback.position)
            if (scrubbing && total > 0L) {
                // The picture follows the finger; this box says where it is going.
                ScrubBubble((scrub.value * total).toLong(), total, playback.position)
            } else if (playback.status == Player.STATE_BUFFERING) {
                CircularProgressIndicator()
            }
        }
        if (!inPip) {
            Column(Modifier.fillMaxWidth().glassBar().padding(horizontal = 16.dp)) {
                SideEffect {
                    if (!scrubbing) scrub.value = if (total == 0L) 0f else (playback.position.toFloat() / total).coerceIn(0f, 1f)
                }
                Slider(
                    state = scrub,
                    enabled = total > 0L && player.isCurrentMediaItemSeekable,
                    onValueChange = { value ->
                        if (!scrubbing) {
                            // Scrubbing mode: fast, frame-accurate-enough seeks while the finger moves.
                            scrubbing = true
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
                        scrubbing = false
                    },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(if (scrubbing) (scrub.value * total).toLong() else playback.position))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player.seekBack() }, enabled = player.isCurrentMediaItemSeekable) {
                            Icon(EzIcons.Rewind, "Seek back")
                        }
                        IconButton(onClick = { player.togglePlayback() }) {
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

/** The PiP window for the current frame; auto-enter only while a video plays. */
private fun playerPipSpec(context: Context, aspect: Float, bounds: ComposeRect?, playing: Boolean): PipSpec =
    PipSpec(aspect, bounds?.toWindowHint(), pipActions(context, playing), autoEnter = playing)

private fun VideoSize.displayAspect(): Float =
    if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else 0f

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
    /** Width over height of the picture, 0 when there is no picture yet. */
    val aspect: Float,
)

private const val SKIP_MS = 10_000L

internal fun formatTime(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$remainder" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$remainder"
}
