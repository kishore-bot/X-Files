package com.xfiles.feature.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzSpacing
import com.xfiles.design.EzTextButton
import com.xfiles.design.PrimaryActionButton
import com.xfiles.design.rememberHaptics

/**
 * The success screen after a job: an animated check that springs in, the output name, the size before and after
 * (when it changed), and the next steps. Open, Share and Save to… appear only where the tool can do them.
 */
@Composable
internal fun ToolResult(
    title: String,
    outputName: String,
    onDoAnother: () -> Unit,
    modifier: Modifier = Modifier,
    sizeBefore: Long? = null,
    sizeAfter: Long? = null,
    onOpen: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onSaveTo: (() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    val pop = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) {
        haptics.confirm()
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = EzSpacing.Gutter, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(104.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
                .background(EzColors.Positive, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CheckMark, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = EzColors.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        Text(
            outputName,
            style = MaterialTheme.typography.titleMedium,
            color = EzColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (sizeBefore != null && sizeAfter != null) SizeChange(sizeBefore, sizeAfter)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onOpen != null) {
                PrimaryActionButton(text = "Open", icon = EzIcons.Document, onClick = onOpen, modifier = Modifier.fillMaxWidth())
            }
            if (onShare != null) EzTextButton(text = "Share", onClick = onShare, modifier = Modifier.fillMaxWidth())
            if (onSaveTo != null) EzTextButton(text = "Save to…", onClick = onSaveTo, modifier = Modifier.fillMaxWidth())
            EzTextButton(text = "Do another", onClick = onDoAnother, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** "1.2 MB → 640 KB, 47% smaller" when the output is smaller; the plain sizes otherwise. */
@Composable
private fun SizeChange(before: Long, after: Long) {
    val saved = if (before > 0 && after < before) ((before - after) * 100 / before).toInt() else null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(formatBytes(before), style = MaterialTheme.typography.titleSmall, color = EzColors.TextSecondary)
        Icon(EzIcons.ChevronRight, contentDescription = null, tint = EzColors.TextSecondary, modifier = Modifier.size(18.dp))
        Text(formatBytes(after), style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
        if (saved != null) {
            Text("$saved% smaller", style = MaterialTheme.typography.labelLarge, color = EzColors.Positive)
        }
    }
}

/** A check mark, drawn from the same Material Symbols path as the About page's check. */
private val CheckMark: ImageVector = ImageVector.Builder(
    name = "Check",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"),
    fill = SolidColor(Color.Black),
).build()
