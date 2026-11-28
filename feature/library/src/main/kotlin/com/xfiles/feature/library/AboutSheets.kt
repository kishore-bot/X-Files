package com.xfiles.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.xfiles.design.EzControlSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.glassCard

/** Every kind of file Xfiles opens: icon, name, extensions and what it does with them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupportedFilesSheet(onDismiss: () -> Unit) {
    EzControlSheet(onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Supported files", style = MaterialTheme.typography.titleLarge, color = EzColors.Text, modifier = Modifier.padding(bottom = 4.dp))
                Text(
                    "Everything opens on this phone, without the internet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(SupportedFiles, key = { it.title }) { group ->
                Row(
                    Modifier.fillMaxWidth().glassCard(strength = 0.45f).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconBadge(group.icon)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(group.title, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                        Text(group.summary, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
                        Text(group.extensions, style = MaterialTheme.typography.labelMedium, color = EzColors.Accent)
                    }
                }
            }
        }
    }
}

/** The components Xfiles is built with, and their licences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicencesSheet(onDismiss: () -> Unit) {
    EzControlSheet(onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("Open-source licences", style = MaterialTheme.typography.titleLarge, color = EzColors.Text, modifier = Modifier.padding(bottom = 4.dp))
                Text(
                    "Xfiles is built with these components. Thank you to everyone who made them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                )
            }
            items(Licences, key = { it.name }) { licence ->
                Column {
                    Text(licence.name, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                    Text(licence.licence, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
                }
            }
        }
    }
}

/** A round badge with an icon in the primary colour, as on the About cards. */
@Composable
internal fun IconBadge(icon: ImageVector) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(EzColors.Selected), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = EzColors.Primary, modifier = Modifier.size(22.dp))
    }
}
