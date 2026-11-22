package com.xfiles.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Semantic touch cues for the moments that matter: a changed choice, a toggle, a task that finished, a task that
 * could not be done. Each cue is a system effect (no custom vibration, so no extra permission), and every call is a
 * no-op while the app's touch-feedback setting ([LocalHapticsEnabled]) is off. Call from the main thread.
 */
@Immutable
class EzHaptics internal constructor(
    private val feedback: HapticFeedback,
    private val enabled: Boolean,
) {
    /** An intentional activation, such as a menu item or a row action. */
    fun activate() = perform(HapticFeedbackType.ContextClick)

    /** A choice in a row of options: a tab, a filter chip, a segment. */
    fun select() = perform(HapticFeedbackType.SegmentTick)

    /** Something switched on or off: a selection, a setting. */
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** A swipe or drag went far enough to act when released. */
    fun threshold() = perform(HapticFeedbackType.GestureThresholdActivate)

    /** A task finished well: saved, exported, created, removed as asked. */
    fun confirm() = perform(HapticFeedbackType.Confirm)

    /** A task could not be done: a failed save, a file that won't open. */
    fun reject() = perform(HapticFeedbackType.Reject)

    private fun perform(type: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(type)
    }
}

/** An [EzHaptics] bound to this composition's haptics and the app's touch-feedback setting. */
@Composable
fun rememberEzHaptics(): EzHaptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(feedback, enabled) { EzHaptics(feedback, enabled) }
}

/**
 * Plays [feedback] when [value] changes to something new, such as a result appearing or an error being set. The
 * value present on first composition (or restored after a rotation) is not replayed.
 */
@Composable
fun <T> HapticOnChange(value: T, feedback: EzHaptics.(T) -> Unit) {
    val haptics = rememberEzHaptics()
    var seen by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != seen) {
            seen = value
            haptics.feedback(value)
        }
    }
}
