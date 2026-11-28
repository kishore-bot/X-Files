package com.xfiles.design

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The app's switch: haptic toggle cue on every change, tinted with the accent colour. */
@Composable
fun EzSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    Switch(
        checked = checked,
        onCheckedChange = { on ->
            haptics.toggle(on)
            onCheckedChange(on)
        },
        modifier = modifier.size(width = 52.dp, height = 32.dp),
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = EzColors.Accent,
            checkedThumbColor = EzColors.OnPrimary,
        ),
    )
}
