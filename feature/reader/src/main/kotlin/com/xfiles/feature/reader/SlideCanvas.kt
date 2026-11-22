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
import com.xfiles.documents.structure.TextSpan
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.runtime.remember
import com.xfiles.design.EzColors

private const val MAX_SLIDE_PICTURE_DIMENSION = 1600

/**
 * What a tap on an interactive slide does. Thumbnails pass none, so they stay inert.
 * [onLink] takes a link as the model encodes it (see [TextSpan.link]); [onMedia] gets the media shape;
 * [onPicture] gets the picture's container path.
 */
internal class SlideActions(
    val onLink: (String) -> Unit,
    val onMedia: (SlideShape) -> Unit,
    val onPicture: (String) -> Unit,
)

/**
 * Draws a slide as designed: [canvas] gives its size in points and
 * background; each shape sits at its own position, scaled to the available
 * width. Shapes with an entrance build appear once [step] reaches theirs;
 * with [animate] they play an entrance effect matching PowerPoint's preset.
 * With [actions], linked text and shapes are tappable, media shapes show a play badge,
 * and pictures open in the zoom viewer.
 */
@Composable
internal fun SlideCanvas(
    canvas: ContentBlock.Slide,
    shapes: List<SlideShape>,
    modifier: Modifier = Modifier,
    step: Int = Int.MAX_VALUE,
    animate: Boolean = false,
    actions: SlideActions? = null,
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
                    ShapeView(shape, scale, actions)
                }
            } else if (shown) {
                ShapeView(shape, scale, actions)
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
private fun ShapeView(shape: SlideShape, scale: Dp, actions: SlideActions?) {
    val outline = shape.line?.let(::Color)
    val fill = shape.fill?.let(::Color)
    val stroke = (shape.lineWidth.coerceAtLeast(0.5f) * scale.value)
    // A shape's own link wins; a plain picture opens the zoom viewer. Media has its badge instead.
    val link = shape.link
    val picture = shape.image
    val tap: (() -> Unit)? = when {
        actions == null -> null
        link != null -> { { actions.onLink(link) } }
        picture != null && shape.media == null -> { { actions.onPicture(picture) } }
        else -> null
    }
    Box(
        Modifier
            .offset(scale * shape.x, scale * shape.y)
            .size(scale * shape.width.coerceAtLeast(0.5f), scale * shape.height.coerceAtLeast(0.5f))
            .graphicsLayer { rotationZ = shape.rotation }
            .then(if (tap != null) Modifier.clickable(role = Role.Button, onClick = tap) else Modifier)
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
            ShapeTextView(shape, scale, actions?.onLink)
        }
        if (actions != null && shape.media != null) {
            SlideMediaBadge(audio = shape.mediaAudio, onClick = { actions.onMedia(shape) }, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ShapeTextView(shape: SlideShape, scale: Dp, onLink: ((String) -> Unit)?) {
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
        for (paragraph in shape.paragraphs) ParagraphView(paragraph, scale, onLink)
    }
}

@Composable
private fun ParagraphView(paragraph: ShapeParagraph, scale: Dp, onLink: ((String) -> Unit)?) {
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
            slideText(paragraph.text.ifEmpty { " " }, paragraph.spans, onLink),
            style = style,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Slide text with its runs styled. Linked runs are drawn in the Primary colour and underlined; with
 * [onLink] they are tappable and report their link.
 */
@Composable
private fun slideText(text: String, spans: List<TextSpan>, onLink: ((String) -> Unit)?): AnnotatedString {
    val linkColor = EzColors.Primary
    return remember(text, spans, onLink, linkColor) {
        buildAnnotatedString {
            append(text)
            for (span in spans) {
                val start = span.start.coerceIn(0, text.length)
                val end = span.end.coerceIn(start, text.length)
                if (start == end) continue
                val decoration = when {
                    span.underline && span.strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    span.underline -> TextDecoration.Underline
                    span.strikethrough -> TextDecoration.LineThrough
                    else -> null
                }
                addStyle(
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        textDecoration = decoration,
                        color = span.color?.let(::Color) ?: Color.Unspecified,
                    ),
                    start,
                    end,
                )
                val url = span.link ?: continue
                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), start, end)
                if (onLink != null) {
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = url,
                            styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                            linkInteractionListener = LinkInteractionListener { onLink(url) },
                        ),
                        start,
                        end,
                    )
                }
            }
        }
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
