package com.xfiles.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.ShapeBuild
import com.xfiles.documents.structure.ShapeGeometry
import com.xfiles.documents.structure.ShapeParagraph
import com.xfiles.documents.structure.SlideShape

private const val MAX_SLIDE_PICTURE_DIMENSION = 1600

/**
 * Draws a slide as designed: [canvas] gives its size in points and
 * background; each shape sits at its own position, scaled to the available
 * width. Shapes with an entrance build appear once [step] reaches theirs;
 * with [animate] they play an entrance effect matching PowerPoint's preset.
 */
@Composable
fun SlideCanvas(
    canvas: ContentBlock.Slide,
    shapes: List<SlideShape>,
    modifier: Modifier = Modifier,
    step: Int = Int.MAX_VALUE,
    animate: Boolean = false,
) {
    if (!canvas.width.isFinite() || !canvas.height.isFinite() || canvas.width <= 0f || canvas.height <= 0f) return
    BoxWithConstraints(
        modifier
            .aspectRatio((canvas.width / canvas.height).coerceIn(0.3f, 4f))
            .background(Color(canvas.background ?: 0xFFFFFFFF.toInt()))
            .clip(RoundedCornerShape(0.dp)),
    ) {
        // Dp per point on this canvas.
        val scale = maxWidth / canvas.width
        canvas.backgroundImage?.let { SlidePicture(it, Modifier.fillMaxSize(), ContentScale.Crop) }
        for (shape in shapes) {
            val build = shape.build
            val shown = build == null || build.step <= step
            if (animate && build != null) {
                // Builds already passed (returning to a slide) show at once; the current one plays.
                val state = remember(shape) { MutableTransitionState(build.step < step) }
                state.targetState = shown
                AnimatedVisibility(state, enter = entrance(build), exit = fadeOut(tween(150))) {
                    ShapeView(shape, scale)
                }
            } else if (shown) {
                ShapeView(shape, scale)
            }
        }
    }
}

/** PowerPoint preset entrance effects mapped to their closest Compose transition. */
private fun entrance(build: ShapeBuild): EnterTransition = when (build.effect) {
    2, 3, 47, 52 -> slideInVertically(tween(450)) { it / 2 } + fadeIn(tween(450)) // fly in, ascend
    21, 23, 53, 55 -> scaleIn(tween(450), initialScale = 0.6f) + fadeIn(tween(450)) // zoom, grow
    22, 12 -> expandHorizontally(tween(450)) + fadeIn(tween(300)) // wipe, split
    else -> fadeIn(tween(500)) // fade, appear and everything else
}

@Composable
private fun ShapeView(shape: SlideShape, scale: Dp) {
    val outline = shape.line?.let(::Color)
    val fill = shape.fill?.let(::Color)
    val stroke = (shape.lineWidth.coerceAtLeast(0.5f) * scale.value)
    Box(
        Modifier
            .offset(scale * shape.x, scale * shape.y)
            .size(scale * shape.width.coerceAtLeast(0.5f), scale * shape.height.coerceAtLeast(0.5f))
            .graphicsLayer { rotationZ = shape.rotation }
            .drawBehind {
                val strokePx = stroke * density
                when (shape.geometry) {
                    ShapeGeometry.Line -> outline?.let {
                        drawLine(it, Offset.Zero, Offset(size.width, size.height), strokeWidth = strokePx)
                    }
                    ShapeGeometry.Ellipse -> {
                        fill?.let { drawOval(it) }
                        outline?.let { drawOval(it, style = Stroke(strokePx)) }
                    }
                    ShapeGeometry.RoundRect -> {
                        val radius = CornerRadius(minOf(size.width, size.height) * 0.16f)
                        fill?.let { drawRoundRect(it, cornerRadius = radius) }
                        outline?.let { drawRoundRect(it, cornerRadius = radius, style = Stroke(strokePx)) }
                    }
                    ShapeGeometry.Rect -> {
                        fill?.let { drawRect(it) }
                        if (shape.table.isEmpty()) outline?.let { drawRect(it, style = Stroke(strokePx)) }
                    }
                }
            },
    ) {
        shape.image?.let { path ->
            val clip = when (shape.geometry) {
                ShapeGeometry.Ellipse -> Modifier.clip(CircleShape)
                ShapeGeometry.RoundRect -> Modifier.clip(RoundedCornerShape(12))
                else -> Modifier
            }
            SlidePicture(path, Modifier.fillMaxSize().then(clip), ContentScale.FillBounds)
        }
        if (shape.table.isNotEmpty()) {
            SlideTable(shape, scale)
        } else if (shape.paragraphs.isNotEmpty()) {
            ShapeTextView(shape, scale)
        }
    }
}

@Composable
private fun ShapeTextView(shape: SlideShape, scale: Dp) {
    val insets = shape.insets
    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = scale * insets.getOrElse(0) { 7.2f },
                top = scale * insets.getOrElse(1) { 3.6f },
                end = scale * insets.getOrElse(2) { 7.2f },
                bottom = scale * insets.getOrElse(3) { 3.6f },
            ),
        verticalArrangement = when (shape.anchor) {
            1 -> Arrangement.Center
            2 -> Arrangement.Bottom
            else -> Arrangement.Top
        },
    ) {
        for (paragraph in shape.paragraphs) ParagraphView(paragraph, scale)
    }
}

@Composable
private fun ParagraphView(paragraph: ShapeParagraph, scale: Dp) {
    val density = LocalDensity.current
    // Point sizes scale with the canvas; toSp keeps the user's font scale neutral.
    val fontSize = with(density) { (scale * paragraph.size).toSp() }
    val lineHeight = fontSize * 1.18f
    val color = paragraph.color?.let(::Color) ?: Color(0xFF17121A)
    val style = androidx.compose.ui.text.TextStyle(
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        fontWeight = if (paragraph.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (paragraph.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = when (paragraph.align) {
            1 -> TextAlign.Center
            2 -> TextAlign.End
            3 -> TextAlign.Justify
            else -> TextAlign.Start
        },
    )
    val indent = scale * (paragraph.level * 24f)
    Row(Modifier.fillMaxWidth().padding(start = indent)) {
        paragraph.bullet?.let { bullet ->
            Text(bullet, style = style.copy(textAlign = TextAlign.Start), modifier = Modifier.width(scale * paragraph.size * 1.2f))
        }
        Text(
            richText(paragraph.text.ifEmpty { " " }, paragraph.spans),
            style = style,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SlideTable(shape: SlideShape, scale: Dp) {
    val density = LocalDensity.current
    val fontSize = with(density) { (scale * 12f).toSp() }
    val border = Color(shape.line ?: 0x40000000)
    Column(Modifier.fillMaxSize().border(1.dp, border)) {
        shape.table.forEachIndexed { index, row ->
            val header = index == 0 && shape.tableHeader
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(if (header) Color(0xFFE60023) else if (index % 2 == 0) Color(0xFFFBE7EE) else Color.White),
            ) {
                row.forEach { cell ->
                    Box(Modifier.weight(1f).fillMaxSize().border(0.5.dp, border).padding(scale * 4f), contentAlignment = Alignment.CenterStart) {
                        Text(
                            cell,
                            fontSize = fontSize,
                            lineHeight = fontSize * 1.15f,
                            color = if (header) Color.White else Color(0xFF17121A),
                            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlidePicture(path: String, modifier: Modifier, scale: ContentScale) {
    val bitmap = embeddedImage(LocalDocumentImages.current[path], MAX_SLIDE_PICTURE_DIMENSION)
    bitmap?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = scale) }
}
