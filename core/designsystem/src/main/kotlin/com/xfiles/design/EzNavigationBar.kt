package com.xfiles.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalHapticsEnabled = staticCompositionLocalOf { true }

class EzNavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

@Composable
fun EzNavigationBar(items: List<EzNavItem>, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val reduced = LocalReducedMotion.current
    val selectedIndex = items.indexOfFirst { it.selected }
    BoxWithConstraints(modifier.fillMaxWidth().floatingGlass(EzShapes.Bar).padding(6.dp)) {
        val itemWidth = maxWidth / items.size.coerceAtLeast(1)
        val pillOffset by animateDpAsState(
            itemWidth * selectedIndex.coerceAtLeast(0),
            if (reduced) snap() else EzMotion.defaultSpatial(),
            label = "navPill",
        )
        if (selectedIndex >= 0) {
            val selectedColor = EzColors.Selected
            val tint = remember(selectedColor) { Brush.verticalGradient(listOf(selectedColor, selectedColor.copy(alpha = 0.7f))) }
            Box(Modifier.matchParentSize()) {
                Box(Modifier.offset(x = pillOffset).width(itemWidth).fillMaxHeight().background(tint, EzShapes.Large))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            for (item in items) {
                val interaction = remember { MutableInteractionSource() }
                val tint by animateColorAsState(
                    if (item.selected) EzColors.Accent else EzColors.Text,
                    if (reduced) snap() else EzMotion.defaultEffects(),
                    label = "navTint",
                )
                Column(
                    Modifier.weight(1f).heightIn(min = 56.dp).pressScale(interaction, 0.92f)
                        .clip(EzShapes.Large)
                        .clickable(interaction, indication = null, role = Role.Tab) {
                            if (!item.selected) haptics.segment()
                            item.onClick()
                        }
                        .semantics { selected = item.selected }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(item.icon, null, Modifier.size(EzSpacing.BarIcon).selectionPop(item.selected, 0.8f), tint)
                    Text(
                        item.label,
                        color = tint,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (item.selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun EzFloatingAction(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Row(
        modifier.heightIn(min = 56.dp)
            .pressScale(interaction, 0.94f).floatingGlass(EzShapes.Pill, 0.65f)
            .clickable(interaction, indication = null, role = Role.Button) {
                haptics.confirm()
                onClick()
            }
            .padding(start = 10.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).glossyPrimary(CircleShape, 4.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = EzColors.OnPrimary, modifier = Modifier.size(22.dp))
        }
        Text(text, color = EzColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
