package com.xfiles.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A section header: muted, small, with room above so groups breathe. Sits on the 4/8 dp grid. */
@Composable
fun EzSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = EzColors.TextSecondary,
        modifier = modifier
            .padding(start = 4.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/**
 * A list row: an optional leading icon in a glass container, a title with an optional supporting line, and an
 * optional trailing slot. When [onClick] is set the row is a button: a haptic tick, the press scale ([pressScale],
 * from Surfaces.kt) and the ripple. Rows are at least 64 dp tall, so the whole row is the touch target.
 */
@Composable
fun EzListItem(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val tappable = if (onClick != null) {
        Modifier
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
            ) {
                haptics.contextClick()
                onClick()
            }
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(tappable)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(44.dp).glass(EzShapes.Small, 0.9f), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = EzColors.Primary, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
            }
        }
        trailing?.invoke()
    }
}
