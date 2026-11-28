package com.xfiles.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.PdfPaper
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.glassCard
import com.xfiles.design.glossyPrimary
import com.xfiles.design.pressScale
import com.xfiles.design.rememberHaptics
import kotlin.math.roundToInt

/**
 * Paper sizes as a row of cards, each with a mini page drawn to its proportions and its size in millimetres.
 * [allowFit] adds a "Fit" card (pictures keep their own shape); it is reported as null.
 */
@Composable
internal fun PaperPicker(
    selected: PdfPaper?,
    enabled: Boolean,
    onSelect: (PdfPaper?) -> Unit,
    modifier: Modifier = Modifier,
    allowFit: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (allowFit) {
            PaperCard(
                label = "Fit",
                caption = "Keeps shape",
                ratio = 0.75f,
                selected = selected == null,
                enabled = enabled,
                fit = true,
                onClick = { onSelect(null) },
            )
        }
        PdfPaper.entries.forEach { paper ->
            PaperCard(
                label = paper.label,
                caption = "${paper.width.toMillimetres()} × ${paper.height.toMillimetres()} mm",
                ratio = paper.width / paper.height,
                selected = selected == paper,
                enabled = enabled,
                fit = false,
                onClick = { onSelect(paper) },
            )
        }
    }
}

/** One paper card: a mini page at the paper's proportions, its name and its size. */
@Composable
private fun PaperCard(
    label: String,
    caption: String,
    ratio: Float,
    selected: Boolean,
    enabled: Boolean,
    fit: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val ink = if (selected) Color.White else EzColors.TextSecondary
    Column(
        Modifier
            .width(96.dp)
            .pressScale(interaction, 0.95f)
            .then(if (selected) Modifier.glossyPrimary(EzShapes.Card, 10.dp) else Modifier.glassCard(EzShapes.Card, 0.45f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
            ) {
                haptics.tick()
                onClick()
            }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.height(64.dp).aspectRatio(ratio), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) Color.White.copy(alpha = 0.92f) else EzColors.Selected)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (fit) {
                    Icon(EzIcons.Image, contentDescription = null, tint = EzColors.Primary, modifier = Modifier.size(22.dp))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(LINES) { line ->
                            Box(
                                Modifier
                                    .fillMaxWidth(if (line == LINES - 1) 0.5f else 1f)
                                    .height(2.dp)
                                    .background(if (selected) EzColors.Primary.copy(alpha = 0.5f) else EzColors.TextSecondary.copy(alpha = 0.4f)),
                            )
                        }
                    }
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) EzColors.OnPrimary else EzColors.Text,
            maxLines = 1,
        )
        Text(caption, style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1)
    }
}

private fun Float.toMillimetres(): Int = (this * 25.4f / 72f).roundToInt()

private const val LINES = 5
