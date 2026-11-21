package com.xfiles.feature.reader

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Picture-in-Picture for the player. MainActivity owns one instance, forwards the system's mode and
 * leave-hint callbacks to it and provides it through [LocalMediaPip]. The player sets the window's
 * PiP parameters from here.
 */
class MediaPip {
    private val inPictureInPicture = MutableStateFlow(false)

    /** True while the activity is shown as a Picture-in-Picture window. */
    val isInPictureInPicture: StateFlow<Boolean> = inPictureInPicture.asStateFlow()

    /** Parameters to enter PiP when the user leaves the app; null when the player is not playing a video. */
    private var leaveParams: PictureInPictureParams? = null

    /** From MainActivity.onPictureInPictureModeChanged. */
    fun onPictureInPictureModeChanged(active: Boolean) {
        inPictureInPicture.value = active
    }

    /**
     * From MainActivity.onUserLeaveHint. API 31+ enters PiP on its own through the auto-enter flag,
     * so this fallback is only for API 30.
     */
    fun onUserLeaveHint(activity: Activity) {
        val params = leaveParams ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) runCatching { activity.enterPictureInPictureMode(params) }
    }

    internal fun update(activity: Activity, spec: PipSpec) {
        val params = spec.toParams()
        leaveParams = params.takeIf { spec.autoEnter }
        runCatching { activity.setPictureInPictureParams(params) }
    }

    internal fun enter(activity: Activity, spec: PipSpec) {
        runCatching { activity.enterPictureInPictureMode(spec.toParams()) }
    }

    internal fun clear(activity: Activity) = update(activity, PipSpec(0f, null, emptyList(), autoEnter = false))
}

/** What the PiP window looks like and whether leaving the app should enter it. */
internal class PipSpec(
    val aspect: Float,
    val sourceRect: Rect?,
    val actions: List<RemoteAction>,
    val autoEnter: Boolean,
) {
    fun toParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio(aspect))
            .setActions(actions)
        // The source rect lets the system animate the window out of the video's place on screen.
        sourceRect?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    private fun ratio(aspect: Float): Rational {
        // Android accepts only 1:2.39 to 2.39:1; a tall phone video would otherwise not open in PiP.
        val shape = if (aspect.isFinite() && aspect > 0f) aspect.coerceIn(0.42f, 2.39f) else 16f / 9f
        return Rational((shape * 1000).roundToInt(), 1000)
    }
}

/** The app's [MediaPip], provided by MainActivity. */
private val UnprovidedMediaPip = MediaPip()

val LocalMediaPip = staticCompositionLocalOf { UnprovidedMediaPip }

/** Converts a Compose window rect into the platform rect that PiP takes as its source hint. */
internal fun ComposeRect.toWindowHint(): Rect =
    Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

/** Plays, or pauses while playing; an ended video starts over. */
internal fun Player.togglePlayback() {
    if (playWhenReady && playbackState != Player.STATE_ENDED) {
        pause()
    } else {
        if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
        play()
    }
}

private const val PIP_PLAY_PAUSE = "com.xfiles.media.PIP_PLAY_PAUSE"
private const val PIP_BACK = "com.xfiles.media.PIP_BACK"
private const val PIP_FORWARD = "com.xfiles.media.PIP_FORWARD"

/** The three buttons on the PiP window: back 10 seconds, play or pause, forward 10 seconds. */
internal fun pipActions(context: Context, playing: Boolean): List<RemoteAction> = listOf(
    pipAction(context, PIP_BACK, android.R.drawable.ic_media_rew, "Back 10 seconds", 1),
    pipAction(
        context,
        PIP_PLAY_PAUSE,
        if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        if (playing) "Pause" else "Play",
        2,
    ),
    pipAction(context, PIP_FORWARD, android.R.drawable.ic_media_ff, "Forward 10 seconds", 3),
)

private fun pipAction(context: Context, action: String, icon: Int, title: String, requestCode: Int): RemoteAction =
    RemoteAction(
        AndroidIcon.createWithResource(context, icon),
        title,
        title,
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        ),
    )

/** Routes the PiP window's buttons to [player] while the player is on screen. */
@Composable
internal fun PipRemoteControls(player: Player) {
    val context = LocalContext.current
    DisposableEffect(player, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    PIP_PLAY_PAUSE -> player.togglePlayback()
                    PIP_BACK -> player.seekBack()
                    PIP_FORWARD -> player.seekForward()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(PIP_PLAY_PAUSE)
            addAction(PIP_BACK)
            addAction(PIP_FORWARD)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}
