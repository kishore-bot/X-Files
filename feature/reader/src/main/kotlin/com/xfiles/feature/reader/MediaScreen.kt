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
    var player by remember(file.uri) { mutableStateOf<ExoPlayer?>(null) }
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

    DisposableEffect(file.uri, lifecycle) {
        val activePlayer = ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context)
                // The phone's own decoders first; FFmpeg only for what they can't play (AC3, DTS, TrueHD...).
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true),
        )
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
        val clampResume = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                val duration = player.duration
                if (player.isCurrentMediaItemSeekable && duration > 0L) {
                    player.removeListener(this)
                    if (player.currentPosition > duration) player.seekTo(duration)
                }
            }
        }
        activePlayer.addListener(clampResume)
        activePlayer.setAudioAttributes(AudioAttributes.DEFAULT, true)
        activePlayer.setHandleAudioBecomingNoisy(true)
        activePlayer.setMediaItem(MediaItem.fromUri(file.uri), resumeAt)
        activePlayer.playWhenReady = resumePlaying
        activePlayer.prepare()
        player = activePlayer
        fun keepPlace() {
            if (activePlayer.playerError == null) {
                resumeAt = activePlayer.resumePosition()
                progress?.save("media-at", resumeAt.toString())
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
            activePlayer.removeListener(clampResume)
            activePlayer.release()
            player = null
        }
    }

    val activePlayer = player
    LaunchedEffect(activePlayer, progress) {
        if (activePlayer == null || progress == null) return@LaunchedEffect
        while (true) {
            delay(4_000)
            if (activePlayer.playerError == null && activePlayer.playbackState != Player.STATE_IDLE) {
                progress.save("media-at", activePlayer.resumePosition().toString())
            }
        }
    }
    if (activePlayer == null) {
        CircularProgressIndicator()
    } else {
        MediaPlayerContent(activePlayer, video, file, onClose, onFily)
    }
}

private fun Player.resumePosition(): Long {
    val position = currentPosition.coerceAtLeast(0L)
    return if (duration > 0L) position.coerceAtMost(duration) else position
}

private const val SKIP_MS = 10_000L
