package com.xfiles.feature.reader

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MediaTopBar(
    title: String,
    onClose: () -> Unit,
    onFily: (() -> Unit)?,
    actions: List<ReaderAction>,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderTopBar(
        title = title,
        backEnabled = true,
        onBack = onClose,
        onFily = onFily,
        actions = actions,
        settingsOpen = menuOpen,
        onSettingsOpen = onMenuOpen,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(8.dp),
    )
}
