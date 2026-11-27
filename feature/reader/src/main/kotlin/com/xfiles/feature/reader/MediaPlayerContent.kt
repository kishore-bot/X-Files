package com.xfiles.feature.reader

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import com.xfiles.design.EzChip
import com.xfiles.design.EzIcons
import com.xfiles.design.EzMotion
import com.xfiles.design.LocalReducedMotion
import com.xfiles.design.LocalFilyOpen
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import android.os.Build
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import com.xfiles.core.documents.FileEntry
import kotlinx.coroutines.delay

/**
 * The player on screen: the picture (or the audio stage), subtitles, gestures, the bars and the sheets. State
 * comes from [MediaPlayerViewModel]; this composable only forwards gestures and taps as events.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaPlayerContent(
    player: ExoPlayer, monitor: MediaOutputMonitor, video: Boolean, file: FileEntry,
    onClose: () -> Unit, onFily: (() -> Unit)?,
    softwarePreferred: Boolean, onSwitchDecoder: () -> Unit,
) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val activity = LocalActivity.current
    val pip = LocalMediaPip.current
    val inPip by pip.isInPictureInPicture.collectAsStateWithLifecycle()
    val viewModel: MediaPlayerViewModel = hiltViewModel(key = file.uri.toString())
    val state by viewModel.state.collectAsStateWithLifecycle()
    val output by monitor.snapshot.collectAsStateWithLifecycle()
    DisposableEffect(player, file.uri, video) {
        viewModel.attach(player, file, video)
        player.prepare()
        onDispose { viewModel.detach() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(monitor, lifecycle, state.playing, output.autoLosslessAvailable) {
        if (!state.playing && !output.autoLosslessAvailable) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                monitor.refresh()
                delay(1_500)
            }
        }
    }
    val hdrDisplay = if (video) rememberHdrDisplay() else null
    val canSwitchDecoder = remember(state.audioTracks, softwarePreferred) {
        softwarePreferred || state.audioTracks.any { track ->
            track.group.getTrackFormat(0).sampleMimeType?.let(FfmpegLibrary::supportsFormat) == true
        }
    }
    var pictureBoost by rememberSaveable(file.uri) { mutableStateOf(false) }
    val hdrSource = state.color.hdr || output.hasHdr10PlusMetadata
    val canBoostPicture = video && !hdrSource && state.color == MediaColor.Sdr && Build.VERSION.SDK_INT >= 31
    val pictureEffect = remember(canBoostPicture, pictureBoost, inPip) {
        if (Build.VERSION.SDK_INT >= 31 && canBoostPicture && pictureBoost && !inPip) sdrPictureEffect() else null
    }

    val haptics = rememberMediaHaptics()
    val device = rememberMediaDevice()
    // The drag lives here, not in the ViewModel: it changes every frame of a swipe. Only its start and end go up.
    val gesture = remember(player) { mutableStateOf<MediaDragState?>(null) }
    var tapSeek by remember(player) { mutableStateOf<Pair<Long, Long>?>(null) }
    var menuOpen by remember(player) { mutableStateOf(false) }
    var transform by remember(player) { mutableStateOf(VideoTransform()) }
    var fullscreen by rememberSaveable(file.uri) { mutableStateOf(video) }
    var videoBounds by remember(player) { mutableStateOf<ComposeRect?>(null) }
    val view = LocalView.current
    val brightBehind = rememberBrightBehindSubtitles(
        file.uri,
        player,
        active = video && state.cues.isNotEmpty() &&
            state.subtitleStyle.color == SubtitleColor.Auto && state.subtitleStyle.background == SubtitleBackground.None,
    )

    // A wide video turns the phone sideways by itself; Lock rotation holds whatever the screen is showing now.
    LaunchedEffect(state.rotationLocked, state.aspect > 1f, video, fullscreen) {
        if (video && activity != null) {
            activity.requestedOrientation = when {
                state.rotationLocked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
                fullscreen && state.aspect > 1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    // Full screen for video: the status and navigation bars hide, and a swipe from the edge shows them briefly.
    DisposableEffect(video, activity, fullscreen, inPip) {
        val window = activity?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        if (video && bars != null) {
            bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen || inPip) bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
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
    val filyOpen = LocalFilyOpen.current
    LaunchedEffect(inPip, filyOpen) {
        if (inPip || filyOpen) {
            menuOpen = false
            viewModel.onEvent(MediaPlayerEvent.OpenSheet(null))
        }
    }

    if (state.failed) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                ExternalOpen(file, message = "This media could not be played in Xfiles.")
            }
            TextButton(onClick = { viewModel.onEvent(MediaPlayerEvent.OpenSheet(MediaSheet.Details)) }) {
                Text("File details")
            }
            if (canSwitchDecoder) TextButton(onClick = onSwitchDecoder) {
                Text(if (softwarePreferred) "Use automatic audio decoding" else "Prefer software audio")
            }
        }
        if (!filyOpen && state.sheet == MediaSheet.Details) {
            MediaDetailsSheet(file, player, state, output, hdrDisplay) {
                viewModel.onEvent(MediaPlayerEvent.OpenSheet(null))
            }
        }
        return
    }

    LaunchedEffect(tapSeek) {
        if (tapSeek != null) {
            delay(850)
            tapSeek = null
        }
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
            Box(
                Modifier.fillMaxSize().clipToBounds()
                    .mediaTapGestures(
                        player = player,
                        haptics = haptics,
                        enabled = !inPip,
                        onToggleControls = {
                            if (menuOpen) menuOpen = false else viewModel.onEvent(MediaPlayerEvent.ToggleControls)
                        },
                        onSkip = { forward ->
                            val from = player.currentPosition
                            if (player.duration > 0L) {
                                val step = if (forward) player.seekForwardIncrement else -player.seekBackIncrement
                                tapSeek = from to (from + step).coerceIn(0L, player.duration)
                            }
                            viewModel.onEvent(if (forward) MediaPlayerEvent.SeekForward else MediaPlayerEvent.SeekBack)
                        },
                        onPlayPause = { viewModel.onEvent(MediaPlayerEvent.PlayPause) },
                        onBoost = { on ->
                            if (!on || !output.usbDirectRequested) viewModel.onEvent(MediaPlayerEvent.Boost(on))
                        },
                    )
                    .mediaDragGestures(
                        player = player,
                        device = device,
                        haptics = haptics,
                        enabled = !inPip && state.sheet == null && !menuOpen,
                        onDrag = onDrag,
                        onSeek = { viewModel.onEvent(MediaPlayerEvent.SeekTo(it)) },
                        transform = transform,
                        allowVolume = !output.usbDirectRequested,
                        fitAspect = state.aspect.takeIf { it > 0f && state.scale == VideoScale.Fit },
                        onTransform = { transform = it },
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Play or pause") {
                                viewModel.onEvent(MediaPlayerEvent.PlayPause)
                                true
                            },
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
            ) {
                ContentFrame(
                    player = player,
                    contentScale = state.scale.scale,
                    surfaceType = if (hdrSource) SURFACE_TYPE_SURFACE_VIEW else SURFACE_TYPE_TEXTURE_VIEW,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val shown = if (inPip) VideoTransform() else transform
                        scaleX = shown.scale
                        scaleY = shown.scale
                        translationX = shown.offset.x
                        translationY = shown.offset.y
                        renderEffect = pictureEffect
                    },
                )
            }
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
            val lift by animateDpAsState(
                state.subtitleStyle.position.lift(state.controlsVisible),
                animationSpec = if (reduced) snap() else EzMotion.fastSpatial(),
                label = "subtitleLift",
            )
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
        GestureOverlay(gesture, total)
        val scrub = state.scrubFraction
        if (scrub != null && total > 0L) {
            // The picture follows the finger; this box says where it is going.
            ScrubBubble((scrub * total).toLong(), total, state.position)
        } else if (gesture.value == null && tapSeek != null && total > 0L && !inPip) {
            tapSeek?.let { (from, target) -> ScrubBubble(target, total, from) }
        } else if (state.playerState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(color = Color.White)
        }
        AnimatedVisibility(
            visible = (state.controlsVisible || menuOpen || !video) && !inPip,
            enter = EzMotion.fadeEnter(reduced),
            exit = EzMotion.fadeExit(reduced || inPip),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                val actions = buildList {
                    if (state.audioTracks.isNotEmpty()) add(ReaderAction("Audio tracks", EzIcons.Audio) {
                        viewModel.onEvent(MediaPlayerEvent.OpenSheet(MediaSheet.Audio))
                    })
                    add(ReaderAction("Playback settings", EzIcons.Music) {
                        viewModel.onEvent(MediaPlayerEvent.OpenSheet(MediaSheet.Playback))
                    })
                    if (video) {
                        add(ReaderAction("Subtitles", EzIcons.Subtitles) {
                            viewModel.onEvent(MediaPlayerEvent.OpenSheet(MediaSheet.Subtitles))
                        })
                        add(ReaderAction("Resize: ${state.scale.label}", EzIcons.AspectRatio) {
                            transform = VideoTransform()
                            viewModel.onEvent(MediaPlayerEvent.SetVideoScale(state.scale.next()))
                        })
                        if (transform.scale > 1f || state.scale != VideoScale.Fit) add(ReaderAction("Reset zoom to fit", EzIcons.FullscreenExit) {
                            transform = VideoTransform()
                            viewModel.onEvent(MediaPlayerEvent.SetVideoScale(VideoScale.Fit))
                        })
                        if (activity != null && context.packageManager.hasSystemFeature(
                                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
                            )
                        ) add(ReaderAction("Picture-in-picture", EzIcons.PictureInPicture) {
                            pip.enter(activity, playerPipSpec(context, state.aspect, videoBounds, state.playing))
                        })
                        add(ReaderAction(
                            if (state.rotationLocked) "Unlock rotation" else "Lock rotation",
                            if (state.rotationLocked) EzIcons.Lock else EzIcons.ScreenLockRotation,
                        ) { viewModel.onEvent(MediaPlayerEvent.ToggleRotationLock) })
                    }
                    add(ReaderAction("File details", EzIcons.Info) {
                        viewModel.onEvent(MediaPlayerEvent.OpenSheet(MediaSheet.Details))
                    })
                }
                Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                    MediaTopBar(
                        title = file.name,
                        onClose = onClose,
                        onFily = onFily,
                        actions = actions,
                        menuOpen = menuOpen,
                        onMenuOpen = { menuOpen = it; viewModel.onEvent(MediaPlayerEvent.TouchControls) },
                    )
                    if (output.usbLosslessDevice != null) {
                        EzChip(
                            label = if (output.usbLosslessEnabled) "USB Lossless" else "USB Audio",
                            selected = output.usbLosslessEnabled,
                            icon = EzIcons.Audio,
                            onClick = { viewModel.onEvent(MediaPlayerEvent.OpenSheet(MediaSheet.Playback)) },
                        )
                    }
                }
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
                    fullscreen = fullscreen.takeIf { video },
                    onFullscreen = {
                        fullscreen = !fullscreen
                        viewModel.onEvent(MediaPlayerEvent.TouchControls)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
    if (!filyOpen) MediaSheetHost(
        state, file, player, device, output, hdrDisplay,
        PlaybackControls(
            canSwitchDecoder, softwarePreferred, onSwitchDecoder, monitor::toggleBoost, monitor::setAutoLossless,
            canBoostPicture, pictureBoost, { pictureBoost = !pictureBoost },
        ),
        viewModel::onEvent,
    )
}


@androidx.annotation.RequiresApi(31)
private fun sdrPictureEffect(): androidx.compose.ui.graphics.RenderEffect {
    val contrast = 1.08f
    val offset = (1f - contrast) * 128f + 4f
    val matrix = android.graphics.ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, offset,
        0f, contrast, 0f, 0f, offset,
        0f, 0f, contrast, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    ))
    return android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(matrix)).asComposeRenderEffect()
}
