package com.xfiles.feature.reader

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import com.xfiles.core.documents.FileEntry

/**
 * The player on screen: the picture (or the audio stage), subtitles, gestures, the bars and the sheets. State
 * comes from [MediaPlayerViewModel]; this composable only forwards gestures and taps as events.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaPlayerContent(player: ExoPlayer, video: Boolean, file: FileEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val pip = LocalMediaPip.current
    val inPip by pip.isInPictureInPicture.collectAsStateWithLifecycle()
    val viewModel: MediaPlayerViewModel = hiltViewModel(key = file.uri.toString())
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(player, file.uri, video) {
        viewModel.attach(player, file, video)
        onDispose { viewModel.detach() }
    }

    val haptics = rememberMediaHaptics()
    val device = rememberMediaDevice()
    // The drag lives here, not in the ViewModel: it changes every frame of a swipe. Only its start and end go up.
    val gesture = remember(player) { mutableStateOf<MediaDragState?>(null) }
    var videoBounds by remember(player) { mutableStateOf<ComposeRect?>(null) }
    val view = LocalView.current
    val brightBehind = rememberBrightBehindSubtitles(
        file.uri,
        player,
        active = video && state.cues.isNotEmpty() &&
            state.subtitleStyle.color == SubtitleColor.Auto && state.subtitleStyle.background == SubtitleBackground.None,
    )

    // A wide video turns the phone sideways by itself; Lock rotation holds whatever the screen is showing now.
    LaunchedEffect(state.rotationLocked, state.aspect > 1f, video) {
        if (video && activity != null) {
            activity.requestedOrientation = when {
                state.rotationLocked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
                state.aspect > 1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    // Full screen for video: the status and navigation bars hide, and a swipe from the edge shows them briefly.
    DisposableEffect(video, activity) {
        val window = activity?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        if (video && bars != null) {
            bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (video) bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    // The screen stays awake while something plays.
    DisposableEffect(view, state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }
    // The PiP window's shape, actions and auto-enter follow the playing video.
    LaunchedEffect(activity, video, state.playing, state.aspect, videoBounds) {
        if (video && activity != null) {
            pip.update(activity, playerPipSpec(context, state.aspect, videoBounds, state.playing))
        }
    }
    DisposableEffect(player, activity) {
        onDispose { if (activity != null) pip.clear(activity) }
    }
    if (video) PipRemoteControls(player)
    // Sheets can't be used in the small Picture-in-Picture window, so they close as it opens.
    LaunchedEffect(inPip) { if (inPip) viewModel.onEvent(MediaPlayerEvent.OpenSheet(null)) }

    if (state.failed) {
        ExternalOpen(file, message = "This media could not be played in Xfiles.")
        return
    }

    val total = state.duration
    val onDrag: (MediaDragState?) -> Unit = { next ->
        if ((gesture.value == null) != (next == null)) viewModel.onEvent(MediaPlayerEvent.SetDragging(next != null))
        gesture.value = next
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { videoBounds = it.boundsInWindow() },
        contentAlignment = Alignment.Center,
    ) {
        if (video) {
            ContentFrame(
                player = player,
                contentScale = state.scale.scale,
                modifier = Modifier
                    .fillMaxSize()
                    .mediaTapGestures(
                        player = player,
                        haptics = haptics,
                        onToggleControls = { viewModel.onEvent(MediaPlayerEvent.ToggleControls) },
                        onSkip = { forward ->
                            viewModel.onEvent(if (forward) MediaPlayerEvent.SeekForward else MediaPlayerEvent.SeekBack)
                        },
                        onBoost = { on -> viewModel.onEvent(MediaPlayerEvent.Boost(on)) },
                    )
                    .mediaDragGestures(
                        player = player,
                        device = device,
                        haptics = haptics,
                        enabled = !inPip,
                        onDrag = onDrag,
                        onSeek = { viewModel.onEvent(MediaPlayerEvent.SeekTo(it)) },
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Back 10 seconds") {
                                viewModel.onEvent(MediaPlayerEvent.SeekBack)
                                true
                            },
                            CustomAccessibilityAction("Forward 10 seconds") {
                                viewModel.onEvent(MediaPlayerEvent.SeekForward)
                                true
                            },
                        )
                    },
            )
        } else {
            MediaAudioStage(
                title = state.title,
                artist = state.artist,
                album = state.album,
                artwork = state.artwork,
                playing = state.playing,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (video && !inPip) {
            // Subtitles lift above the controls while they show, or sit where the style's position puts them.
            val lift by animateDpAsState(state.subtitleStyle.position.lift(state.controlsVisible), label = "subtitleLift")
            SubtitleText(
                state.cues,
                state.subtitleStyle,
                brightBehind,
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(bottom = lift, start = 24.dp, end = 24.dp),
            )
        }
        GestureOverlay(gesture, total, state.position)
        val scrub = state.scrubFraction
        if (scrub != null && total > 0L) {
            // The picture follows the finger; this box says where it is going.
            ScrubBubble((scrub * total).toLong(), total, state.position)
        } else if (state.playerState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(color = Color.White)
        }
        AnimatedVisibility(
            visible = (state.controlsVisible || !video) && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                MediaTopBar(
                    title = state.title,
                    subtitle = if (video) {
                        state.audioSummary
                    } else {
                        listOfNotNull(state.artist, state.album).joinToString(" · ").ifEmpty { null }
                    },
                    video = video,
                    subtitlesOn = state.subtitlesOn,
                    scale = state.scale,
                    rotationLocked = state.rotationLocked,
                    onClose = onClose,
                    onSheet = { viewModel.onEvent(MediaPlayerEvent.OpenSheet(it)) },
                    onScale = { viewModel.onEvent(MediaPlayerEvent.CycleScale) },
                    onPictureInPicture = {
                        if (activity != null) pip.enter(activity, playerPipSpec(context, state.aspect, videoBounds, state.playing))
                    },
                    onRotationLock = {
                        haptics.toggle(!state.rotationLocked)
                        viewModel.onEvent(MediaPlayerEvent.ToggleRotationLock)
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                MediaTransport(
                    playing = state.playing,
                    seekable = state.seekable,
                    position = state.position,
                    duration = total,
                    scrubFraction = state.scrubFraction,
                    repeatOne = if (video) null else state.repeatOne,
                    onBack = { viewModel.onEvent(MediaPlayerEvent.SeekBack) },
                    onToggle = { viewModel.onEvent(MediaPlayerEvent.PlayPause) },
                    onForward = { viewModel.onEvent(MediaPlayerEvent.SeekForward) },
                    onRepeat = { viewModel.onEvent(MediaPlayerEvent.ToggleRepeat) },
                    onScrub = { viewModel.onEvent(MediaPlayerEvent.Scrub(it)) },
                    onScrubFinished = { viewModel.onEvent(MediaPlayerEvent.ScrubFinished) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
    MediaSheetHost(state, file, player, device, viewModel::onEvent)
}
