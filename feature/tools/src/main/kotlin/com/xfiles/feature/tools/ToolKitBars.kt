package com.xfiles.feature.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzMotion
import com.xfiles.design.EzShapes
import com.xfiles.design.EzSpacing
import com.xfiles.design.EzTopBar
import com.xfiles.design.glass
import com.xfiles.design.glassBar
import com.xfiles.design.glossyPrimary
import com.xfiles.design.pressScale
import com.xfiles.design.rememberHaptics

/**
 * The skeleton of every tool: a top bar, the scrolling body ([content], where the hero, the steps and the cards go)
 * and a sticky action bar at the bottom ([bottomBar]), so the primary action is always in reach.
 */
@Composable
internal fun ToolScaffold(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight().imePadding()) {
        EzTopBar(title = title, onBack = onBack, backLabel = backLabel, gutter = 0.dp)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = EzSpacing.Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        bottomBar()
    }
}

/**
 * The sticky action bar: one large gradient button that states the action ("Merge 3 files", "Compress · ~2.1 MB").
 * While [running], the same pill fills with progress rather than swapping for a spinner. Disabled until [enabled].
 */
@Composable
internal fun ToolActionBar(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    running: Boolean = false,
    progress: Float? = null,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val fill by animateFloatAsState(
        targetValue = if (running) (progress ?: 0.12f).coerceIn(0.04f, 1f) else 0f,
        animationSpec = EzMotion.defaultSpatial(),
        label = "actionFill",
    )
    val live = enabled && !running
    Box(modifier.fillMaxWidth().glassBar(EzShapes.Bar).padding(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .pressScale(interaction)
                .clip(EzShapes.Pill)
                .then(if (live) Modifier.glossyPrimary(EzShapes.Pill) else Modifier.glass(EzShapes.Pill, 0.8f))
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = live,
                    role = Role.Button,
                ) {
                    haptics.confirm()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            // The progress fill: a lighter band that grows across the pill while the job runs.
            if (running) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White.copy(alpha = 0.22f)))
            }
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (icon != null && !running) {
                    Icon(icon, contentDescription = null, tint = labelColor(live), modifier = Modifier.size(20.dp))
                }
                Text(
                    if (running) "Working… ${((progress ?: 0f) * 100).toInt()}%" else label,
                    style = MaterialTheme.typography.titleMedium,
                    color = labelColor(live),
                )
            }
        }
    }
}

@Composable
private fun labelColor(live: Boolean): Color = if (live) EzColors.OnPrimary else EzColors.TextSecondary
