package com.xfiles.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconToggleButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/** Control chrome over changing content such as video, photos, and pages. */
@Composable
fun EzControlBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides EzColors.Text) {
        Row(
            modifier.fillMaxWidth().glassBar(strength = 0.96f).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

class EzControlAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** An anchored, icon-only menu. Short rows fit narrow windows, with accessible names and long-press labels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EzControlMenu(actions: List<EzControlAction>, open: Boolean, onOpen: (Boolean) -> Unit) {
    if (actions.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Box {
        IconToggleButton(
            checked = open,
            onCheckedChange = onOpen,
            colors = IconToggleButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = colors.primaryContainer,
                checkedContentColor = colors.onPrimaryContainer,
            ),
        ) { Icon(EzIcons.Settings, "Viewer settings") }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { onOpen(false) },
            properties = PopupProperties(focusable = false),
            shape = MaterialTheme.shapes.large,
            containerColor = colors.surfaceContainerHigh,
        ) {
            actions.chunked(3).forEach { row ->
                Row(Modifier.padding(horizontal = 4.dp)) {
                    row.forEach { action ->
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                            tooltip = { PlainTooltip { Text(action.label) } },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = { onOpen(false); action.onClick() }) {
                                Icon(action.icon, action.label, tint = colors.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A modal surface for controls above a viewer. The platform sheet consumes its own system insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EzControlSheet(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = EzShapes.Sheet,
        containerColor = colors.surfaceContainerHigh.copy(alpha = 0.97f),
        contentColor = colors.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        content = content,
    )
}
