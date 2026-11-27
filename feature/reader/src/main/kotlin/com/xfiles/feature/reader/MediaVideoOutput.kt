package com.xfiles.feature.reader

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

internal enum class MediaColor(val label: String, val hdr: Boolean) {
    Unknown("Color format unreported", false),
    Sdr("SDR", false),
    Pq("PQ HDR", true),
    Hlg("HLG", true),
    DolbyVision("Dolby Vision", true),
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun Format?.mediaColor(): MediaColor = when {
    this?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> MediaColor.DolbyVision
    else -> when (this?.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> MediaColor.Pq
        C.COLOR_TRANSFER_HLG -> MediaColor.Hlg
        C.COLOR_TRANSFER_SDR -> MediaColor.Sdr
        else -> MediaColor.Unknown
    }
}

@Composable
internal fun rememberHdrDisplay(): String? {
    val view = LocalView.current
    val manager = LocalContext.current.getSystemService(DisplayManager::class.java)
    var result by remember(view) { mutableStateOf(view.display.hdrLabel()) }
    DisposableEffect(view, manager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { result = view.display.hdrLabel() }
            override fun onDisplayRemoved(displayId: Int) { result = view.display.hdrLabel() }
            override fun onDisplayChanged(displayId: Int) { result = view.display.hdrLabel() }
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    return result
}

private fun Display?.hdrLabel(): String? = this?.hdrCapabilities?.supportedHdrTypes?.mapNotNull {
    when (it) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        else -> null
    }
}?.joinToString()?.ifBlank { "SDR display" }
