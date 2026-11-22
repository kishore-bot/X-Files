package com.xfiles.feature.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/** How long a press may last and still count as a tap. */
private const val TAP_MAX_MILLIS = 350L

/**
 * Reports a tap that nothing below it acted on, as the fraction of the width it landed at (0 at the
 * left edge, 1 at the right). Runs in the final pass, after the text and the pictures have had the
 * event, so a link or a mark that took the tap is not also a page turn. A drag is not a tap.
 */
internal fun Modifier.onUnhandledTap(onTap: (fractionX: Float) -> Unit): Modifier = pointerInput(onTap) {
    val slop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        var downAt: Offset? = null
        var downMillis = 0L
        var moved = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull() ?: continue
            val start = downAt
            when {
                change.changedToDownIgnoreConsumed() -> {
                    downAt = change.position
                    downMillis = change.uptimeMillis
                    moved = false
                }
                change.changedToUpIgnoreConsumed() -> {
                    if (start != null && !moved && !change.isConsumed && change.uptimeMillis - downMillis < TAP_MAX_MILLIS) {
                        onTap(change.position.x / size.width.toFloat().coerceAtLeast(1f))
                    }
                    downAt = null
                }
                start != null && change.pressed && (change.position - start).getDistance() > slop -> moved = true
            }
        }
    }
}
