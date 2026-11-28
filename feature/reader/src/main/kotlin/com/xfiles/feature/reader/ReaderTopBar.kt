package com.xfiles.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.BarIconButton
import com.xfiles.design.EzControlAction
import com.xfiles.design.EzControlBar
import com.xfiles.design.EzControlMenu
import com.xfiles.design.EzIcons
import com.xfiles.design.FilyButton
import com.xfiles.design.LocalFilyOpen

internal typealias ReaderAction = EzControlAction

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
    EzControlBar(modifier) {
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

@Composable
internal fun ViewerSettings(actions: List<ReaderAction>, open: Boolean, onOpen: (Boolean) -> Unit) {
    val visible = open && !LocalFilyOpen.current && actions.isNotEmpty()
    BackHandler(enabled = visible) { onOpen(false) }
    EzControlMenu(actions, visible, onOpen)
}
