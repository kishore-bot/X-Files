package com.xfiles.design

import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalFilyOpen = compositionLocalOf { false }

@Composable
fun FilyButton(onClick: () -> Unit) {
    val open = LocalFilyOpen.current
    val colors = MaterialTheme.colorScheme
    IconToggleButton(
        checked = open,
        onCheckedChange = { onClick() },
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = colors.primaryContainer,
            checkedContentColor = colors.onPrimaryContainer,
        ),
    ) {
        Icon(EzIcons.Mic, if (open) "Close Fily" else "Ask Fily")
    }
}
