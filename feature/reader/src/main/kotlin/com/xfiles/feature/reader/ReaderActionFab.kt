package com.xfiles.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xfiles.design.EzIcons
import com.xfiles.design.glass
import com.xfiles.design.glassPanel
import com.xfiles.design.rememberHaptics
import kotlin.math.abs

/** One entry of the reader's action menu: its label and icon, and what it does. */
internal class ReaderAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The reader's one floating action button: a glass round button in the bottom-right corner that opens a
 * column of labelled actions above it with a spring expand. [actions] are in the order they are shown.
 */
@Composable
internal fun ReaderActionButton(
    actions: List<ReaderAction>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .glass(RoundedCornerShape(50), 0.8f)
                            .clickable(role = Role.Button) {
                                haptics.tick()
                                onOpenChange(false)
                                action.onClick()
                            }
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(action.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Icon(action.icon, action.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .glass(CircleShape, 0.9f)
                .clickable(role = Role.Button) {
                    haptics.toggle(!open)
                    onOpenChange(!open)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (open) EzIcons.Close else EzIcons.Plus,
                if (open) "Close actions" else "Actions",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A full-screen scrim behind the open action menu; tapping it closes the menu. */
@Composable
internal fun ReaderActionScrim(onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    )
}

/**
 * Watches the reader's touches without taking them: a downward drag of the content (a scroll up) shows the
 * action button, an upward drag (a scroll down) hides it, and a tap toggles it. Observes in the initial pass.
 */
internal fun Modifier.chromeGestures(onScrollDown: () -> Unit, onScrollUp: () -> Unit, onTap: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var pending = 0f
                var travel = 0f
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    pressed = change.pressed
                    val dy = change.position.y - change.previousPosition.y
                    pending += dy
                    travel += abs(dy)
                    if (pending > DRAG_STEP_PX) {
                        onScrollUp()
                        pending = 0f
                    } else if (pending < -DRAG_STEP_PX) {
                        onScrollDown()
                        pending = 0f
                    }
                }
                if (travel >= TAP_SLOP_PX) continue
                // A double tap belongs to the content (zoom on pictures and pages), so it must not toggle the
                // button twice: wait briefly for a second press and, if one comes, let it pass without a toggle.
                val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                }
                if (second == null) {
                    onTap()
                } else {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.id == second.id && it.pressed })
                }
            }
        }
    }

private const val DRAG_STEP_PX = 24f
private const val TAP_SLOP_PX = 12f

/** The page style chooser: four swatches in a glass panel. Picking one applies it to this file type. */
@Composable
internal fun PageStyleSheet(current: PageStyle, onPick: (PageStyle) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .glassPanel(RoundedCornerShape(28.dp), elevation = 10.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Page style", style = MaterialTheme.typography.titleMedium)
            Text(
                "Applies to every file of this type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PageStyle.entries.forEach { style ->
                    StyleSwatch(style, selected = style == current) {
                        haptics.segment()
                        onPick(style)
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleSwatch(style: PageStyle, selected: Boolean, onClick: () -> Unit) {
    val fixed = style.colors()
    val page = fixed?.page ?: MaterialTheme.colorScheme.surfaceVariant
    val ink = fixed?.text ?: MaterialTheme.colorScheme.onSurface
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(page)
                .border(2.dp, ring, CircleShape)
                .clickable(role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = ink, style = MaterialTheme.typography.titleMedium)
        }
        Text(style.label, style = MaterialTheme.typography.labelMedium)
    }
}
