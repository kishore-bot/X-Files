package com.xfiles.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.xfiles.design.rememberHaptics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzNavItem
import com.xfiles.design.EzShapes
import com.xfiles.design.GlassIconButton
import com.xfiles.design.LocalHapticsEnabled
import com.xfiles.design.floatingGlass
import com.xfiles.design.pressScale

@Composable
internal fun LandscapeLibraryRail(
    items: List<EzNavItem>,
    onOpen: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val hapticsEnabled = LocalHapticsEnabled.current
    Box(modifier.fillMaxHeight().width(112.dp).padding(4.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.width(80.dp).floatingGlass(EzShapes.Bar, 0.7f).padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (item in items) {
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(interaction)
                            .clip(EzShapes.Medium)
                            .then(if (item.selected) Modifier.background(EzColors.Selected, EzShapes.Medium) else Modifier)
                            .clickable(interaction, indication = null, role = Role.Tab) {
                                if (hapticsEnabled && !item.selected) haptics.segment() 
                                item.onClick()
                            }
                            .semantics { selected = item.selected }
                            .padding(horizontal = 2.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(item.icon, null, Modifier.size(22.dp), if (item.selected) EzColors.Accent else EzColors.Text)
                        Text(item.label, color = EzColors.Text, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassIconButton(EzIcons.Folder, "Open file", onOpen, tint = EzColors.Primary)
                GlassIconButton(EzIcons.Info, "About Xfiles", onAbout, tint = EzColors.Primary)
            }
        }
    }
}
