package com.xfiles.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.glassCard

/** A feature on the About screen's Upcoming tab. Every entry is planned, not yet shipped. */
private data class UpcomingItem(val icon: ImageVector, val title: String, val description: String)

private val PlannedItems = listOf(
    UpcomingItem(EzIcons.Lock, "Password-protected ZIPs", "Create and open ZIP files that need a password."),
    UpcomingItem(EzIcons.Archive, "7z and TAR archives", "Compress and extract 7z and TAR files with the ZIP tools."),
    UpcomingItem(
        EzIcons.Movie,
        "Show subtitles earlier",
        "Negative subtitle delay, so lines can appear before the speech. Limited by the video engine.",
    ),
)

/** The Upcoming tab: what the team has planned, one row each with an icon, a line and a Planned chip. */
@Composable
internal fun UpcomingScreen() {
    LazyColumn(
        Modifier.widthIn(max = 620.dp).fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("heading") {
            Text(
                "Planned",
                style = MaterialTheme.typography.titleLarge,
                color = EzColors.Text,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp).semantics { heading() },
            )
        }
        items(PlannedItems, key = { it.title }) { UpcomingRow(it) }
    }
}

@Composable
private fun UpcomingRow(item: UpcomingItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .glassCard(EzShapes.Card, 0.5f)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(EzColors.Selected, EzShapes.Small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, contentDescription = null, Modifier.size(24.dp), tint = EzColors.Accent)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
            Spacer(Modifier.height(2.dp))
            Text(item.description, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Planned",
            style = MaterialTheme.typography.labelLarge,
            color = EzColors.Accent,
            modifier = Modifier
                .background(EzColors.Selected, EzShapes.Pill)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
