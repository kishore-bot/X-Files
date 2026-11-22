package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.glassCard

/**
 * One choice in a player sheet: a glass row with a check when [selected]. [detail] is the second line; a
 * [warning] detail (a reason the row is off) reads in the error colour. Disabled rows are dimmed and don't react.
 */
@Composable
internal fun MediaChoiceRow(
    title: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean = true,
    warning: Boolean = false,
    cue: (MediaHaptics) -> Unit = { it.tick() },
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val haptics = rememberMediaHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .glassCard(shape, strength = if (selected) 0.7f else 0.45f)
            .clip(shape)
            .background(if (selected) EzColors.Primary.copy(alpha = 0.12f) else Color.Transparent)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button) {
                cue(haptics)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(MediaIcons.Check, null, Modifier.size(20.dp), tint = EzColors.Primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning) MaterialTheme.colorScheme.error else EzColors.TextSecondary,
                )
            }
        }
    }
}

/** A small caps heading between groups of rows. */
@Composable
internal fun MediaSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = EzColors.TextSecondary,
        modifier = modifier.padding(top = 8.dp, start = 4.dp),
    )
}
