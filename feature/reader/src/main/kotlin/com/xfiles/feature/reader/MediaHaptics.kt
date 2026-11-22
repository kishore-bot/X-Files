package com.xfiles.feature.reader

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.xfiles.design.LocalHapticsEnabled

/**
 * The player's touch cues, each mapped to the closest HapticFeedbackConstants the phone supports. Every cue is a
 * system effect and does nothing while the app's touch-feedback setting is off.
 */
internal class MediaHaptics(private val view: View, private val enabled: Boolean) {

    /** A tick for a small, ordinary action: a button, a choice, the play toggle. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** A tick for each step while scrubbing or skipping; SEGMENT_TICK where the system has it. */
    fun segment() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        },
    )

    /** A task finished: a track chosen, a subtitle loaded. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** A task could not be done: a file that won't play, a subtitle that won't load. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    /** Something switched on or off: repeat, the subtitle level, rotation lock. */
    fun toggle(on: Boolean) = perform(
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.CONTEXT_CLICK
            on -> HapticFeedbackConstants.TOGGLE_ON
            else -> HapticFeedbackConstants.TOGGLE_OFF
        },
    )

    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** A swipe over the picture has started. */
    fun gestureStart() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_START
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        },
    )

    /** A swipe over the picture has ended. */
    fun gestureEnd() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_END
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}

@Composable
internal fun rememberMediaHaptics(): MediaHaptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { MediaHaptics(view, enabled) }
}
