package com.xfiles.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.xfiles.design.BarIconButton
import com.xfiles.design.EzIcons
import com.xfiles.design.FilyButton
import com.xfiles.design.LocalFilyOpen
import com.xfiles.design.glassBar

internal class ReaderAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
internal fun ReaderTopBar(
    title: String,
    backEnabled: Boolean,
    onBack: () -> Unit,
    onFily: (() -> Unit)?,
    actions: List<ReaderAction>,
    settingsOpen: Boolean,
    onSettingsOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().glassBar().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(EzIcons.Back, "Back to library", onBack, enabled = backEnabled)
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onFily != null) FilyButton {
            onSettingsOpen(false)
            onFily()
        }
        ViewerSettings(actions, settingsOpen, onSettingsOpen)
    }
}

/** A small popup anchored to the settings icon, with labelled touch and accessibility actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerSettings(actions: List<ReaderAction>, open: Boolean, onOpen: (Boolean) -> Unit) {
    if (actions.isEmpty()) return
    val filyOpen = LocalFilyOpen.current
    BackHandler(enabled = open && !filyOpen) { onOpen(false) }
    Box {
        BarIconButton(EzIcons.Settings, "Viewer settings", { onOpen(!open) })
        DropdownMenu(
            expanded = open && !filyOpen,
            properties = PopupProperties(focusable = false),
            onDismissRequest = { onOpen(false) },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            actions.chunked(4).forEach { row ->
                Row(Modifier.padding(horizontal = 4.dp)) {
                    row.forEach { action ->
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                            tooltip = { PlainTooltip { Text(action.label) } },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = { onOpen(false); action.onClick() }) {
                                Icon(action.icon, action.label)
                            }
                        }
                    }
                }
            }
        }
    }
}
