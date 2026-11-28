package com.xfiles.feature.reader

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.xfiles.core.documents.FileEntry

/**
 * Hosts one media file: creates the ExoPlayer while the file is open, keeps its place across rotation and process
 * death, pauses it when the app leaves the screen, and hands the player to [MediaPlayerContent].
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaScreen(file: FileEntry, video: Boolean, onClose: () -> Unit, onFily: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val pip = LocalMediaPip.current
    val progress = LocalViewerProgress.current
    var session by remember(file.uri) { mutableStateOf<AndroidMediaSession?>(null) }
    var preferSoftware by rememberSaveable(file.uri) { mutableStateOf(false) }
    var retried by rememberSaveable(file.uri) { mutableStateOf(false) }
    var handoff by remember(file.uri) { mutableStateOf<AudioRecovery?>(null) }
    fun switchAudio(active: ExoPlayer, software: Boolean) {
        if (preferSoftware == software || session?.player !== active) return
        handoff = AudioRecovery(active.currentMediaItem, active.resumePosition(), active.playWhenReady, active.trackSelectionParameters)
        preferSoftware = software
    }
    // Survives rotation and process death, so the video picks up where it was instead of restarting.
    var resumeAt by rememberSaveable(file.uri) { mutableLongStateOf(progress?.read("media-at")?.toLongOrNull()?.coerceAtLeast(0) ?: 0L) }
    var resumePlaying by rememberSaveable(file.uri) { mutableStateOf(false) }

    // The orientation the reader had comes back when the player closes.
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation
        onDispose {
            if (activity != null) activity.requestedOrientation = original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(file.uri, lifecycle, preferSoftware) {
        val recovery = handoff
        val activePlayer = ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context)
                // The phone's own decoders first; FFmpeg only for what they can't play (AC3, DTS, TrueHD...).
                .setExtensionRendererMode(
                    if (preferSoftware) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                )
                .setEnableDecoderFallback(true),
        )
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
        val playbackListener = object : Player.Listener {
            private var clamped = false

            override fun onPlayerError(error: PlaybackException) {
                if (retried || preferSoftware || !canRetryAudio(activePlayer, error)) return
                retried = true
                switchAudio(activePlayer, software = true)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val duration = player.duration
                if (!clamped && player.isCurrentMediaItemSeekable && duration > 0L) {
                    clamped = true
                    if (player.currentPosition > duration) player.seekTo(duration)
                }
            }
        }
        activePlayer.addListener(playbackListener)
        activePlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(if (video) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        activePlayer.setHandleAudioBecomingNoisy(true)
        recovery?.let { activePlayer.trackSelectionParameters = it.tracks }
        activePlayer.setMediaItem(recovery?.item ?: MediaItem.fromUri(file.uri), recovery?.position ?: resumeAt)
        activePlayer.playWhenReady = recovery?.play ?: resumePlaying
        handoff = null
        val monitor = MediaOutputMonitor(context, activePlayer, video)
        monitor.start()
        session = AndroidMediaSession(activePlayer, monitor)
        fun keepPlace() {
            if (activePlayer.playerError == null) {
                resumeAt = activePlayer.resumePosition()
                progress?.let { it.store.savePlayback(it.file, resumeAt, activePlayer.duration) }
            }
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
            activePlayer.removeListener(playbackListener)
            monitor.close()
            activePlayer.release()
            session = null
        }
    }

    val activeSession = session
    val activePlayer = activeSession?.player
    LaunchedEffect(activePlayer, progress) {
        if (activePlayer == null || progress == null) return@LaunchedEffect
        while (true) {
            delay(4_000)
            if (activePlayer.playerError == null && activePlayer.playbackState != Player.STATE_IDLE) {
                progress.store.savePlayback(progress.file, activePlayer.resumePosition(), activePlayer.duration)
            }
        }
    }
    if (activeSession == null) {
        CircularProgressIndicator()
    } else {
        MediaPlayerContent(activeSession.player, activeSession.output, video, file, onClose, onFily, preferSoftware) {
            switchAudio(activeSession.player, software = !preferSoftware)
        }
    }
}

private fun Player.resumePosition(): Long {
    val position = currentPosition.coerceAtLeast(0L)
    return if (duration > 0L) position.coerceAtMost(duration) else position
}

private const val SKIP_MS = 10_000L

private data class AndroidMediaSession(val player: ExoPlayer, val output: MediaOutputMonitor)

private data class AudioRecovery(val item: MediaItem?, val position: Long, val play: Boolean, val tracks: TrackSelectionParameters)

@androidx.annotation.OptIn(UnstableApi::class)
private fun canRetryAudio(player: ExoPlayer, error: PlaybackException): Boolean {
    val renderer = error as? ExoPlaybackException
    val rendererMime = renderer?.rendererFormat?.sampleMimeType
    val sinkError = error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
    if (!sinkError && !MimeTypes.isAudio(rendererMime.orEmpty()) &&
        renderer?.rendererName?.contains("AudioRenderer") != true) return false
    val mime = rendererMime ?: player.audioFormat?.sampleMimeType ?: player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        ?.getTrackFormat(0)?.sampleMimeType
    return mime?.let(FfmpegLibrary::supportsFormat) == true
}
