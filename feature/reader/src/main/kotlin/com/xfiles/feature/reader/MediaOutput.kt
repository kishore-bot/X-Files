package com.xfiles.feature.reader

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.MediaFormat
import android.media.Spatializer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class MediaOutput(
    val audioDecoder: String? = null,
    val videoDecoder: String? = null,
    val hasHdr10PlusMetadata: Boolean = false,
    val ffmpegVersion: String? = null,
    val route: String? = null,
    val outputFormat: String? = null,
    val spatial: String? = null,
    val headTrackingAvailable: Boolean = false,
    val canBoost: Boolean = false,
    val boosted: Boolean = false,
    val autoLosslessAvailable: Boolean = false,
    val autoLossless: Boolean = true,
    val usbLosslessDevice: String? = null,
    val usbLosslessStatus: String? = null,
    val usbLosslessEnabled: Boolean = false,
    val usbDirectRequested: Boolean = false,
    val notice: String? = null,
)

@androidx.annotation.OptIn(UnstableApi::class)
internal class MediaOutputMonitor(
    private val context: Context,
    private val player: ExoPlayer,
    private val video: Boolean,
) : AnalyticsListener, Player.Listener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val settings = context.getSharedPreferences("media_output", Context.MODE_PRIVATE)
    private var autoLossless = settings.getBoolean("auto_lossless_usb", true)
    private val state = MutableStateFlow(MediaOutput(
        ffmpegVersion = FfmpegLibrary.getVersion(),
        autoLosslessAvailable = Build.VERSION.SDK_INT >= 34,
        autoLossless = autoLossless,
    ))
    val snapshot = state.asStateFlow()
    private val tracks = ArrayDeque<TrackOutput>()
    private val track get() = tracks.lastOrNull()
    private var started = false
    private var enhancer: LoudnessEnhancer? = null
    private var enhancerSession = C.AUDIO_SESSION_ID_UNSET
    private var boostWanted = false
    private var usbDevice: AudioDeviceInfo? = null
    private var usbAttempt: UsbFormat? = null
    private var cachedUsbDevice: Int? = null
    private var cachedUsbMixers: List<AudioMixerAttributes> = emptyList()
    private var unsubscribeSpatial: (() -> Unit)? = null
    // The callback runs on the playback thread. It publishes only the first metadata observation.
    private val metadata = VideoFrameMetadataListener { _, _, _, mediaFormat ->
        if (!state.value.hasHdr10PlusMetadata && mediaFormat?.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO) == true) {
            state.update { it.copy(hasHdr10PlusMetadata = true) }
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = usbDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = usbDevicesChanged()
    }

    fun start() {
        started = true
        player.addAnalyticsListener(this)
        player.addListener(this)
        if (video) player.setVideoFrameMetadataListener(metadata)
        audio?.registerAudioDeviceCallback(devices, Handler(player.applicationLooper))
        if (Build.VERSION.SDK_INT >= 32) subscribeSpatial()
        refresh()
    }

    fun close() {
        started = false
        player.removeListener(this)
        player.removeAnalyticsListener(this)
        if (video) player.clearVideoFrameMetadataListener(metadata)
        audio?.unregisterAudioDeviceCallback(devices)
        unsubscribeSpatial?.invoke()
        clearUsbDirect()
        releaseBoost()
    }

    override fun onEvents(player: Player, events: Player.Events) = refresh()

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        val label = if (decoderName.startsWith("ffmpeg", ignoreCase = true)) "$decoderName · software"
            else codecLabel(decoderName)
        state.update { it.copy(audioDecoder = label) }
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        state.update { it.copy(videoDecoder = codecLabel(decoderName)) }
    }

    override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        if (state.value.audioDecoder?.substringBefore(" · ") == decoderName) state.update { it.copy(audioDecoder = null) }
    }

    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        if (state.value.videoDecoder?.substringBefore(" · ") == decoderName) state.update { it.copy(videoDecoder = null) }
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        if (tracks.size == 8) tracks.removeFirst()
        tracks.addLast(audioTrackConfig.toOutput())
        refresh()
    }

    override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        // Media3 may release the previous track after initializing the replacement.
        val index = tracks.indexOf(audioTrackConfig.toOutput())
        if (index >= 0) tracks.removeAt(index)
        refresh()
    }

    override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) = refresh()

    fun toggleBoost() {
        boostWanted = !boostWanted
        state.update { it.copy(notice = null) }
        refresh()
        if (boostWanted && enhancer == null) {
            boostWanted = false
            state.update { it.copy(notice = "Audio boost is unavailable for this output.") }
        }
    }

    fun setAutoLossless(enabled: Boolean) {
        autoLossless = enabled
        settings.edit().putBoolean("auto_lossless_usb", enabled).apply()
        usbAttempt = null
        if (!enabled) clearUsbDirect()
        refresh()
    }

    private fun usbDevicesChanged() {
        cachedUsbDevice = null
        refresh()
    }

    fun refresh() {
        if (!started) return
        val attributes = player.audioAttributes.platformAudioAttributes
        val routes = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { audio?.getAudioDevicesForAttributes(attributes).orEmpty() }.getOrDefault(emptyList())
        } else emptyList()
        val usb = if (Build.VERSION.SDK_INT >= 34) updateUsbLossless(routes) else null
        val output = track
        val pcm = output != null && output.encoding in pcmEncodings && !output.offload
        val session = player.audioSessionId
        val canBoost = pcm && session > 0 && usbDevice == null
        if (!canBoost || !boostWanted || enhancerSession != session) releaseBoost()
        if (canBoost && boostWanted && enhancer == null) {
            enhancer = runCatching { LoudnessEnhancer(session) }.getOrNull()?.let { effect ->
                if (runCatching { effect.setTargetGain(300); effect.setEnabled(true) == 0 }.getOrDefault(false)) effect
                else { runCatching { effect.release() }; null }
            }
            enhancerSession = session
        }
        val format = if (pcm && output != null) runCatching {
            AudioFormat.Builder().setEncoding(output.encoding).setSampleRate(output.sampleRate)
                .setChannelMask(output.channelConfig).build()
        }.getOrNull() else null
        val spatializer = if (Build.VERSION.SDK_INT >= 32) audio?.spatializer else null
        val spatial = if (Build.VERSION.SDK_INT >= 32 && spatializer != null && format != null && usbDevice == null) {
            when {
                !spatializer.isAvailable || !spatializer.canBeSpatialized(attributes, format) -> "Unavailable for this output"
                spatializer.isEnabled -> "Enabled for this format"
                else -> "Available · off in system settings"
            }
        } else null
        val headTracking = Build.VERSION.SDK_INT >= 33 && spatializer?.isHeadTrackerAvailable == true && spatial != null
        val label = routes.map { it.productName.toString() }.filter(String::isNotBlank).joinToString().ifBlank { null }
        val outputFormat = output?.let {
            val encoding = pcmLabel(it.encoding) ?: if (it.offload) "Offload" else "Encoded output"
            "$encoding · ${it.sampleRate} Hz · ${Integer.bitCount(it.channelConfig)} ch"
        }
        state.update {
            it.copy(
                route = label, outputFormat = outputFormat, spatial = spatial, headTrackingAvailable = headTracking,
                canBoost = canBoost, boosted = enhancer != null, autoLossless = autoLossless,
                usbLosslessDevice = usb?.device, usbLosslessStatus = usb?.status,
                usbLosslessEnabled = usb?.enabled == true, usbDirectRequested = usbDevice != null,
            )
        }
    }

    @RequiresApi(34)
    private fun updateUsbLossless(routes: List<AudioDeviceInfo>): UsbLossless? {
        val manager = audio ?: return null
        val device = routes.singleOrNull()?.takeIf { it.type in usbTypes }
        if (device?.id != cachedUsbDevice) {
            cachedUsbDevice = device?.id
            cachedUsbMixers = if (device == null) emptyList() else runCatching {
                manager.getSupportedMixerAttributes(device).filter {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                }
            }.getOrDefault(emptyList())
        }
        if (device == null || cachedUsbMixers.isEmpty()) {
            clearUsbDirect()
            usbAttempt = null
            return null
        }
        val name = device.productName.toString().ifBlank { "USB audio device" }
        if (usbDevice?.id != device.id) clearUsbDirect()
        if (!autoLossless) return UsbLossless(name, "Automatic lossless is off")

        val source = player.audioFormat
        val output = track
        if (source == null) {
            clearUsbDirect()
            usbAttempt = null
            return UsbLossless(name, "Waiting for audio")
        }
        if (output == null) return UsbLossless(name, "Waiting for audio output")
        val encoding = source.losslessPcmEncoding()
        val unchanged = encoding != null && source.sampleRate == output.sampleRate &&
            source.channelCount in 1..2 && source.channelCount == Integer.bitCount(output.channelConfig) &&
            encoding == output.encoding && !output.offload && !output.tunneling &&
            player.volume == 1f && player.playbackParameters.speed == 1f &&
            player.playbackParameters.pitch == 1f && !player.skipSilenceEnabled && !boostWanted
        val mixer = if (unchanged) cachedUsbMixers.firstOrNull {
            it.format.encoding == output.encoding && it.format.sampleRate == output.sampleRate &&
                it.format.channelMask == output.channelConfig
        } else null
        if (mixer == null) {
            clearUsbDirect()
            usbAttempt = null
            return UsbLossless(name, "Track or playback settings don’t match the USB mixer")
        }

        val requested = UsbFormat(device.id, output.encoding, output.sampleRate, output.channelConfig)
        val attributes = player.audioAttributes.platformAudioAttributes
        if (usbAttempt != requested) {
            clearUsbDirect()
            usbAttempt = requested
            if (runCatching { manager.setPreferredMixerAttributes(attributes, device, mixer) }.getOrDefault(false)) {
                usbDevice = device
            }
        }
        val confirmed = usbDevice != null && runCatching {
            val preferred = manager.getPreferredMixerAttributes(attributes, device)
            preferred != null && preferred.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                preferred.format == mixer.format
        }.getOrDefault(false)
        val status = when {
            confirmed -> "USB lossless mode enabled"
            usbDevice != null -> "USB lossless mode requested"
            else -> "USB lossless mode unavailable"
        }
        return UsbLossless(name, status, confirmed)
    }

    private data class UsbLossless(val device: String, val status: String, val enabled: Boolean)
    private data class UsbFormat(val device: Int, val encoding: Int, val rate: Int, val channelMask: Int)

    private fun clearUsbDirect() {
        val device = usbDevice ?: return
        usbDevice = null
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { audio?.clearPreferredMixerAttributes(player.audioAttributes.platformAudioAttributes, device) }
        }
    }

    private fun releaseBoost() {
        enhancer?.let { runCatching { it.release() } }
        enhancerSession = C.AUDIO_SESSION_ID_UNSET
        enhancer = null
    }

    @RequiresApi(32)
    private fun subscribeSpatial() {
        val spatializer = audio?.spatializer ?: return
        val listener = object : Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) = refresh()
            override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) = refresh()
        }
        spatializer.addOnSpatializerStateChangedListener(context.mainExecutor, listener)
        val removeHead = if (Build.VERSION.SDK_INT >= 33) subscribeHeadTracker(spatializer) else null
        unsubscribeSpatial = { spatializer.removeOnSpatializerStateChangedListener(listener); removeHead?.invoke() }
    }

    @RequiresApi(33)
    private fun subscribeHeadTracker(spatializer: Spatializer): () -> Unit {
        val listener = Spatializer.OnHeadTrackerAvailableListener { _, _ -> refresh() }
        spatializer.addOnHeadTrackerAvailableListener(context.mainExecutor, listener)
        return { spatializer.removeOnHeadTrackerAvailableListener(listener) }
    }

    private fun codecLabel(name: String): String = runCatching {
        val info = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == name }
        when {
            info?.isHardwareAccelerated == true -> "$name · hardware"
            info?.isSoftwareOnly == true -> "$name · software"
            else -> name
        }
    }.getOrDefault(name)

    private data class TrackOutput(
        val encoding: Int,
        val sampleRate: Int,
        val channelConfig: Int,
        val offload: Boolean,
        val tunneling: Boolean,
        val bufferSize: Int,
    )

    private fun AudioSink.AudioTrackConfig.toOutput() =
        TrackOutput(encoding, sampleRate, channelConfig, offload, tunneling, bufferSize)

    private companion object {
        val pcmEncodings = setOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        val usbTypes = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}

internal fun pcmLabel(encoding: Int): String? = when (encoding) {
    C.ENCODING_PCM_8BIT -> "8-bit PCM"
    C.ENCODING_PCM_16BIT -> "16-bit PCM"
    C.ENCODING_PCM_24BIT -> "24-bit PCM"
    C.ENCODING_PCM_32BIT -> "32-bit PCM"
    C.ENCODING_PCM_FLOAT -> "32-bit float PCM"
    else -> null
}


/** Only use formats whose decoded PCM precision can be established from the source. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun Format.losslessPcmEncoding(): Int? {
    val encoding = when (sampleMimeType) {
        MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_ALAC -> pcmEncoding
        MimeTypes.AUDIO_FLAC -> {
            val data = initializationData.firstOrNull() ?: return null
            // Media3 supplies the fLaC marker and 34-byte STREAMINFO block with its header.
            if (data.size < 42 || data[0] != 0x66.toByte() || data[1] != 0x4c.toByte() ||
                data[2] != 0x61.toByte() || data[3] != 0x43.toByte() ||
                (data[4].toInt() and 0x7f) != 0 || data[5].toInt() != 0 ||
                data[6].toInt() != 0 || data[7].toInt() != 34) return null
            // STREAMINFO packs the five bits for precision after the sample rate and channel count.
            val bits = (((data[20].toInt() and 1) shl 4) or ((data[21].toInt() and 0xf0) shr 4)) + 1
            Util.getPcmEncoding(bits)
        }
        else -> return null
    }
    return encoding.takeIf { it == C.ENCODING_PCM_16BIT || it == C.ENCODING_PCM_24BIT || it == C.ENCODING_PCM_32BIT }
}
