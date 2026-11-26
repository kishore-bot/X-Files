package com.xfiles.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/*
 * Shared controls of the library and the tools. Each one is a glass surface
 * from Surfaces.kt; nothing here blurs or animates per frame except the press
 * and selection springs, which only redraw the layer.
 */

/** Round glass button holding one icon (the header's mic and info). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = EzColors.Text,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction, 0.9f)
            .floatingGlass(CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.tick()
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(24.dp))
    }
}

/** Plain icon button for use inside a glass bar, drawn at 40dp (touch area widens to 48dp). */
@Composable
fun BarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = EzColors.Text,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        modifier = Modifier
            .size(48.dp)
            .pressScale(interaction, 0.85f)
            .clip(CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.tick()
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(EzSpacing.BarIcon))
    }
}

/** Filter chip: glass when idle, glossy red when selected, with an optional type icon. */
@Composable
fun EzChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = EzColors.Text,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val base = modifier
        .heightIn(min = 40.dp)
        .pressScale(interaction)
        .selectionPop(selected)
    val surface = if (selected) base.glossyPrimary(EzShapes.Pill, 8.dp) else base.glassCard(EzShapes.Pill, 0.4f)
    Row(
        modifier = surface
            .clickable(interaction, indication = null, role = Role.Tab) {
                if (!selected) haptics.segment()
                onClick()
            }
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) EzColors.OnPrimary else iconTint, modifier = Modifier.size(17.dp))
        }
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) EzColors.OnPrimary else EzColors.Text,
            maxLines = 1,
        )
    }
}

/** A tertiary text action (Clear, Cancel): accent text, no container. */
@Composable
fun EzTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = EzColors.Accent,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        modifier
            .heightIn(min = EzSpacing.Touch)
            .pressScale(interaction, 0.94f)
            .clip(EzShapes.Pill)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else EzColors.TextSecondary.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The screen's main action, in the red gradient. Use one per screen. */
@Composable
fun PrimaryActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val surface = if (enabled) Modifier.glossyPrimary(EzShapes.Pill, 14.dp) else Modifier.glass(EzShapes.Pill, 0.6f)
    val content = if (enabled) EzColors.OnPrimary else EzColors.TextSecondary.copy(alpha = 0.6f)
    val haptics = rememberHaptics()
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .pressScale(interaction, 0.94f)
            .then(surface)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.confirm()
                onClick()
            }
            .padding(PaddingValues(horizontal = if (icon != null) 24.dp else 18.dp, vertical = 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(22.dp))
        Text(text, color = content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** A section heading in a full-width glass pill, with an optional count and trailing control. */
@Composable
fun EzSectionPill(
    title: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    compact: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (compact) Modifier.heightIn(min = 28.dp)
                else Modifier.glassCard(EzShapes.Pill, 0.55f).padding(start = 18.dp, end = 6.dp).heightIn(min = 48.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = EzColors.Text,
            modifier = Modifier.semantics { heading() },
        )
        if (count != null) Text(count, style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
        trailing()
    }
}

/**
 * Coloured file-type badge: a page with a folded corner and the extension, in
 * the type's colour. The colours are the same in both themes; white text on top.
 */
@Composable
fun FileTypeTile(label: String, colors: Pair<Color, Color>, modifier: Modifier = Modifier) {
    val top = colors.first
    val bottom = colors.second
    Box(
        modifier = modifier
            .size(width = 40.dp, height = 46.dp)
            .raised(BadgeShape, 4.dp, bottom)
            .clip(BadgeShape)
            .background(Brush.linearGradient(listOf(top, bottom)))
            .drawBehind {
                val fold = size.width * 0.32f
                drawPath(
                    Path().apply {
                        moveTo(size.width - fold, 0f)
                        lineTo(size.width, fold)
                        lineTo(size.width - fold, fold)
                        close()
                    },
                    BadgeFold,
                )
                drawRect(BadgeGloss, Offset.Zero, size.copy(height = size.height * 0.5f))
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            label,
            color = EzBadgeColors.Ink,
            fontSize = if (label.length > 3) 9.sp else 10.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

private val BadgeShape = RoundedCornerShape(9.dp)
private val BadgeFold = EzBadgeColors.Ink.copy(alpha = 0.45f)
private val BadgeGloss = Brush.verticalGradient(listOf(EzBadgeColors.Ink.copy(alpha = 0.28f), EzBadgeColors.Ink.copy(alpha = 0.04f)))

/** A centred glass card for empty and status states. */
@Composable
fun EzEmptyCard(
    title: String,
    body: String?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .glass(EzShapes.Card, 0.5f)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = EzColors.Primary, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = EzColors.Text, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary, textAlign = TextAlign.Center)
        content()
    }
}

/**
 * The glass top bar: back, a one-line title (with an optional line beneath),
 * then [actions]. Its icon buttons are [BarIconButton]s.
 */
@Composable
fun EzTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Back",
    gutter: Dp = EzSpacing.Gutter,
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = 8.dp)
            .glassBar()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(EzIcons.Back, backLabel, onBack)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = EzColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            subtitle?.invoke()
        }
        actions()
    }
}

/** A glass dialog with a title, a text and two text actions (dismiss, then confirm). */
@Composable
fun EzDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismiss: () -> Unit = onDismissRequest,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .glassPanel(EzShapes.Dialog)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = EzColors.Text, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
            Text(text, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                EzTextButton(dismissText, onDismiss, color = EzColors.TextSecondary)
                EzTextButton(confirmText, onConfirm)
            }
        }
    }
}
