package com.xfiles.feature.tools

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzMotion
import com.xfiles.design.EzShapes
import com.xfiles.design.EzSpacing
import com.xfiles.design.glass
import com.xfiles.design.staggeredEnter

/** The three steps every tool walks through, shown as a stepper under the hero. */
enum class ToolStep(val label: String) { Pick("Pick"), Adjust("Adjust"), Save("Save") }

/** "1 Pick → 2 Adjust → 3 Save": completed and current steps take the brand red. */
@Composable
internal fun ToolStepper(current: ToolStep, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolStep.entries.forEachIndexed { index, step ->
            val active = step == current
            val reached = step.ordinal <= current.ordinal
            val badge by animateColorAsState(
                targetValue = if (reached) EzColors.Primary else EzColors.FieldOutline,
                animationSpec = EzMotion.defaultEffects(),
                label = "stepBadge",
            )
            Row(
                Modifier
                    .weight(1f)
                    .glass(EzShapes.Pill, if (active) 0.9f else 0.35f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(24.dp).background(badge, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                Text(
                    step.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) EzColors.Text else EzColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The top of every tool: the tool's coloured icon in a large glass circle, its title, a line on what it does and the
 * stepper. Enters with the list's stagger.
 */
@Composable
internal fun ToolHero(
    icon: ImageVector,
    tint: Color,
    title: String,
    blurb: String,
    step: ToolStep,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .staggeredEnter(0)
            .padding(horizontal = EzSpacing.Gutter, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(88.dp).glass(CircleShape, 0.9f), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = EzColors.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        Text(
            blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = EzColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        ToolStepper(step, Modifier.padding(top = 8.dp))
    }
}
