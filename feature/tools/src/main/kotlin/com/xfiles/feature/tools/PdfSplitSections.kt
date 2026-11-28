package com.xfiles.feature.tools

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.glassCard
import com.xfiles.design.glossyPrimary
import com.xfiles.design.pressScale
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.PdfPageSpan
import com.xfiles.documents.PdfSplitPlan

/** Every page, every N pages, or custom cuts, as three cards. Custom is where the page grid's taps apply. */
@Composable
internal fun SplitModeCards(
    plan: PdfSplitPlan,
    enabled: Boolean,
    onPlan: (PdfSplitPlan) -> Unit,
    onCustom: () -> Unit,
) {
    val everyN = plan as? PdfSplitPlan.EveryPages
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val onePerPage = plan == PdfSplitPlan.OnePerPage
        ChoiceCard(
            title = "Every page",
            detail = "One PDF per page",
            selected = onePerPage,
            enabled = enabled,
            onClick = { onPlan(PdfSplitPlan.OnePerPage) },
            modifier = Modifier.weight(1f),
            leading = { ModeGlyph(EzIcons.Grid, onePerPage) },
        )
        ChoiceCard(
            title = "Every N",
            detail = "A PDF every N pages",
            selected = everyN != null,
            enabled = enabled,
            onClick = { onPlan(PdfSplitPlan.EveryPages(everyN?.pagesText ?: "2")) },
            modifier = Modifier.weight(1f),
            leading = { ModeGlyph(EzIcons.ListLines, everyN != null) },
        )
        val custom = plan is PdfSplitPlan.Ranges
        ChoiceCard(
            title = "Custom",
            detail = "Tap where a PDF ends",
            selected = custom,
            enabled = enabled,
            onClick = onCustom,
            modifier = Modifier.weight(1f),
            leading = { ModeGlyph(EzIcons.Edit, custom) },
        )
    }
}

@Composable
private fun ModeGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean) {
    Icon(
        icon,
        contentDescription = null,
        tint = if (selected) EzColors.OnPrimary else EzColors.Primary,
        modifier = Modifier.size(22.dp),
    )
}

/** Pages per part, changed by a stepper in whole pages. */
@Composable
internal fun EveryNStepper(pagesText: String, pageCount: Int, enabled: Boolean, onChange: (String) -> Unit) {
    val n = pagesText.toIntOrNull() ?: 2
    val most = pageCount.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().glassCard(EzShapes.Pill, 0.5f).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = { onChange((n - 1).coerceAtLeast(1).toString()) }, enabled = enabled && n > 1) {
            Icon(EzIcons.Minus, contentDescription = "Fewer pages per PDF", tint = EzColors.Primary)
        }
        Text(
            "$n ${if (n == 1) "page" else "pages"} per PDF",
            style = MaterialTheme.typography.titleMedium,
            color = EzColors.Text,
        )
        IconButton(onClick = { onChange((n + 1).coerceAtMost(most).toString()) }, enabled = enabled && n < most) {
            Icon(EzIcons.Plus, contentDescription = "More pages per PDF", tint = EzColors.Primary)
        }
    }
}

/**
 * Every page as a thumbnail. Tapping a page ends a PDF after it; the cut pages glow. In the other modes the cuts
 * shown are the parts the plan makes, and a tap switches to custom from them.
 */
@Composable
internal fun SplitPageGrid(
    pageCount: Int,
    cuts: Set<Int>,
    enabled: Boolean,
    preview: suspend (Int) -> Bitmap,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(count = pageCount, key = { it }) { index ->
            SplitPageCell(
                index = index,
                cut = index in cuts,
                enabled = enabled,
                preview = preview,
                onTap = { onTap(index) },
            )
        }
    }
}

@Composable
private fun SplitPageCell(
    index: Int,
    cut: Boolean,
    enabled: Boolean,
    preview: suspend (Int) -> Bitmap,
    onTap: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val ink = if (cut) EzColors.OnPrimary else EzColors.Text
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.95f)
            .then(if (cut) Modifier.glossyPrimary(EzShapes.Card, 8.dp) else Modifier.glassCard(EzShapes.Card, 0.45f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
            ) {
                haptics.tick()
                onTap()
            }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PreviewTile(
            key = index,
            load = { preview(index) },
            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
            description = "Page ${index + 1}",
        )
        Text("Page ${index + 1}", style = MaterialTheme.typography.labelMedium, color = ink)
        Text(
            if (cut) "Part ends here" else "Continues",
            style = MaterialTheme.typography.labelSmall,
            color = if (cut) EzColors.OnPrimary else EzColors.TextSecondary,
        )
    }
}

/** One card per part: its page span and an editable file name. */
@Composable
internal fun SplitPartNames(
    spans: List<PdfPageSpan>,
    names: List<String>,
    enabled: Boolean,
    onName: (Int, String) -> Unit,
) {
    spans.forEachIndexed { index, span ->
        Column(
            Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.45f).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Part ${index + 1} · pages ${span.start + 1}–${span.end + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = EzColors.TextSecondary,
            )
            OutlinedTextField(
                value = names[index],
                onValueChange = { onName(index, it) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                label = { Text("File name") },
            )
        }
    }
}
