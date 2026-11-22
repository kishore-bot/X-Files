package com.xfiles.feature.tools

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.glass
import com.xfiles.design.glassCard
import com.xfiles.design.pressScale
import com.xfiles.design.rememberHaptics

/**
 * The drop-zone pick card: a large glass card with a dashed border that crawls slowly, an icon badge, a title and a
 * hint. Tapping opens the system picker.
 */
@Composable
internal fun FilePickCard(
    title: String,
    hint: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val phase: Float by rememberInfiniteTransition(label = "dropZone").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing), RepeatMode.Restart),
        label = "dashPhase",
    )
    Column(
        modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .glass(EzShapes.Card, 0.6f)
            .drawBehind {
                // Dashes are drawn inside the card so the stroke is never clipped at the edge.
                val stroke = 2.dp.toPx()
                val cycle = 24.dp.toPx()
                val radius = 20.dp.toPx()
                drawRoundRect(
                    color = tint.copy(alpha = 0.75f),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(cycle * 0.55f, cycle * 0.45f), phase * cycle),
                    ),
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
            ) {
                haptics.contextClick()
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(64.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = EzColors.Text, textAlign = TextAlign.Center)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary, textAlign = TextAlign.Center)
    }
}

/**
 * A picked file: a first-page thumbnail (or a file glyph), its name, its meta line (pages and size) and the controls
 * to move it up or down and to remove it. [onMoveUp]/[onMoveDown] are null where the order does not matter.
 */
@Composable
internal fun PickedFileCard(
    name: String,
    meta: String,
    thumbnail: ImageBitmap?,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    Row(
        modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.5f).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 68.dp)
                .clip(EzShapes.Page)
                .background(EzColors.Selected),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(bitmap = thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(EzIcons.Document, contentDescription = null, tint = EzColors.Primary, modifier = Modifier.size(26.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onMoveUp != null) {
            IconButton(onClick = { haptics.tick(); onMoveUp() }) {
                Icon(EzIcons.ChevronUp, contentDescription = "Move up", tint = EzColors.TextSecondary)
            }
        }
        if (onMoveDown != null) {
            IconButton(onClick = { haptics.tick(); onMoveDown() }) {
                Icon(EzIcons.ChevronDown, contentDescription = "Move down", tint = EzColors.TextSecondary)
            }
        }
        if (onRemove != null) {
            IconButton(onClick = { haptics.reject(); onRemove() }) {
                Icon(EzIcons.Close, contentDescription = "Remove", tint = EzColors.TextSecondary)
            }
        }
    }
}
