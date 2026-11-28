package com.xfiles.feature.reader

import androidx.compose.runtime.Composable
import com.xfiles.design.Haptics
import com.xfiles.design.rememberHaptics

/** The player uses the app-wide [Haptics]; these names keep the media files' call sites short. */
internal typealias MediaHaptics = Haptics

@Composable
internal fun rememberMediaHaptics(): MediaHaptics = rememberHaptics()
