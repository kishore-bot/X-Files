package com.xfiles.feature.reader

import android.content.ContentResolver
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Window brightness and media volume, steered by the player's gestures. Brightness belongs to this
 * window and volume to the music stream, so both are handed back when the player is not in front.
 */
internal class MediaDevice(private val window: Window?, private val audio: AudioManager?) {
    private val originalBrightness = window?.attributes?.screenBrightness ?: SYSTEM_BRIGHTNESS
    private val maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1

    /** The level this player set; re-applied when the window comes back. Null until a gesture sets one. */
    private var applied: Float? = null

    /** The system's screen brightness, read off the main thread when the player opens. Null until read. */
    @Volatile private var systemBrightness: Float? = null

    /**
     * The brightness a drag starts from: this player's own level, else the window's override, else the
     * system's real level, else half.
     */
    fun brightness(): Float = applied
        ?: window?.attributes?.screenBrightness?.takeIf { it >= 0f }
        ?: systemBrightness
        ?: DEFAULT_BRIGHTNESS

    /** Reads Settings.System.SCREEN_BRIGHTNESS (0..255) on IO; it is a provider query, never a main-thread call. */
    suspend fun loadSystemBrightness(resolver: ContentResolver) {
        val raw = withContext(Dispatchers.IO) {
            try {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            } catch (_: Settings.SettingNotFoundException) {
                -1
            }
        }
        if (raw >= 0) systemBrightness = (raw / 255f).coerceIn(MIN_BRIGHTNESS, 1f)
    }

    fun setBrightness(level: Float) {
        applied = level.coerceIn(MIN_BRIGHTNESS, 1f)
        applyBrightness()
    }

    fun volume(): Float = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maxVolume

    fun setVolume(level: Float) {
        val index = (level.coerceIn(0f, 1f) * maxVolume).roundToInt()
        // Some devices and policies refuse media volume changes; the gesture then simply does nothing.
        try {
            audio?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        } catch (_: SecurityException) {
        }
    }

    /** Gives the screen's brightness back to the system while the window is in the background. */
    fun pauseBrightness() {
        window?.let { it.attributes = it.attributes.apply { screenBrightness = originalBrightness } }
    }

    fun resumeBrightness() {
        if (applied != null) applyBrightness()
    }

    fun release() {
        pauseBrightness()
        applied = null
    }

    private fun applyBrightness() {
        val level = applied ?: return
        window?.let { it.attributes = it.attributes.apply { screenBrightness = level } }
    }

    private companion object {
        const val SYSTEM_BRIGHTNESS = -1f
        const val DEFAULT_BRIGHTNESS = 0.5f
        const val MIN_BRIGHTNESS = 0.05f
    }
}

@Composable
internal fun rememberMediaDevice(): MediaDevice {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val device = remember(activity) {
        MediaDevice(activity?.window, context.getSystemService(AudioManager::class.java))
    }
    // The real brightness, read once when the player opens, so the first drag starts from it.
    LaunchedEffect(device) { device.loadSystemBrightness(context.contentResolver) }
    DisposableEffect(device, activity, lifecycle) {
        val previousStream = activity?.volumeControlStream
        // The hardware volume keys change the music stream while the player is in front.
        activity?.volumeControlStream = AudioManager.STREAM_MUSIC
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> device.pauseBrightness()
                Lifecycle.Event.ON_RESUME -> device.resumeBrightness()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            device.release()
            if (activity != null && previousStream != null) activity.volumeControlStream = previousStream
        }
    }
    return device
}
