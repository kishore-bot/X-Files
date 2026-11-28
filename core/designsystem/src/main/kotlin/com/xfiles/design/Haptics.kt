package com.xfiles.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role

/**
 * Semantic haptic cues backed by [View.performHapticFeedback]. Newer constants (API 30 / 34) are used when the
 * device supports them, with fallbacks to the long-standing constants on older releases.
 *
 * Every call is a no-op while the user's "Haptics" setting ([LocalHapticsEnabled]) is off.
 * Call from the main thread.
 */
@Immutable
class Haptics internal constructor(
    private val view: View,
    private val enabled: Boolean,
) {
    /** A light tick for ordinary taps: buttons, rows, nav items. */
    fun tick() = perform(tickConstant())

    /** A task finished well: saved, created, exported. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** A task could not be done: failed save, unsupported file. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    /** Something switched on or off: switches, toggles, checkboxes. */
    fun toggle(on: Boolean) = perform(
        if (Build.VERSION.SDK_INT >= 30) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )

    /** A press-and-hold was recognised. */
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** A swipe or gesture began being tracked. */
    fun gestureStart() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CLOCK_TICK,
    )

    /** A swipe or gesture was released. */
    fun gestureEnd() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK,
    )

    /** A step along a segmented control, slider or picker. */
    fun segment() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else tickConstant(),
    )

    /** A contextual activation such as a menu item or row action. */
    fun contextClick() = perform(
        if (Build.VERSION.SDK_INT >= 23) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** A key press on an on-screen keyboard or keypad. */
    fun keyboardTap() = perform(
        if (Build.VERSION.SDK_INT >= 26) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** An item was picked up for dragging (reorder, selection drag). */
    fun dragStart() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.LONG_PRESS,
    )

    /** Plays the cue for a semantic [kind]. */
    fun play(kind: HapticKind) = when (kind) {
        HapticKind.Tick -> tick()
        HapticKind.Confirm -> confirm()
        HapticKind.Reject -> reject()
        HapticKind.Segment -> segment()
        HapticKind.ContextClick -> contextClick()
        HapticKind.LongPress -> longPress()
        HapticKind.DragStart -> dragStart()
    }

    private fun tickConstant(): Int =
        if (Build.VERSION.SDK_INT >= 21) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.VIRTUAL_KEY

    private fun perform(constant: Int) {
        if (!enabled) return
        view.performHapticFeedback(constant)
    }
}

/** Semantic kind of haptic cue, used by [Modifier.hapticClickable]. */
enum class HapticKind { Tick, Confirm, Reject, Segment, ContextClick, LongPress, DragStart }

/**
 * Plays [feedback] when [value] changes to something new, such as a result appearing or an error being set. The
 * value present on first composition (or restored after a rotation) is not replayed.
 */
@Composable
fun <T> HapticOnChange(value: T, feedback: Haptics.(T) -> Unit) {
    val haptics = rememberHaptics()
    var seen by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != seen) {
            seen = value
            haptics.feedback(value)
        }
    }
}

/** A [Haptics] bound to this composition's view and the app's "Haptics" setting. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}

/**
 * A clickable that plays a haptic cue ([feedback], a light tick by default) before invoking [onClick].
 * Use it in place of `Modifier.clickable` anywhere a tap should feel physical.
 */
fun Modifier.hapticClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    feedback: HapticKind = HapticKind.Tick,
    onClick: () -> Unit,
): Modifier = composed {
    val haptics = rememberHaptics()
    clickable(enabled = enabled, onClickLabel = onClickLabel, role = role) {
        haptics.play(feedback)
        onClick()
    }
}
