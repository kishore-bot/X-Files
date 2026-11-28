package com.xfiles.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Shared tint and edge treatments. Screens that overlap scrolling content can add a
// FrostedBackdrop beneath selected controls; ordinary rows and cards use these fills alone.

/**
 * Frosted-glass surface. [strength] 0..1 moves from subtle to nearly opaque.
 * Content is clipped to [shape].
 */
@Composable
fun Modifier.glass(shape: Shape, strength: Float = 0.45f): Modifier {
    val palette = LocalEzPalette.current
    return this.then(remember(palette, shape, strength) { glassLayers(palette, shape, strength) })
}

/** The glass of every top and bottom bar, with a soft shadow, so they all read as one family. */
@Composable
fun Modifier.glassBar(shape: Shape = EzShapes.Bar, strength: Float = 0.45f): Modifier {
    val palette = LocalEzPalette.current
    return this.then(
        remember(palette, shape, strength) {
            Modifier.chromeShadow(shape, palette.glassShadow, 3.dp)
                .then(glassLayers(palette, shape, strength))
        },
    )
}

/** Detached controls: a low elevation shadow, with the same readable glass tint. */
@Composable
fun Modifier.floatingGlass(shape: Shape = EzShapes.Pill, strength: Float = 0.55f): Modifier {
    val palette = LocalEzPalette.current
    return this.then(
        remember(palette, shape, strength) {
            Modifier.chromeShadow(shape, palette.glassShadow, 4.dp)
                .then(glassLayers(palette, shape, strength))
        },
    )
}

/** A glass row or card in a list: a lighter shadow than a bar. */
@Composable
fun Modifier.glassCard(shape: Shape = EzShapes.Card, strength: Float = 0.5f): Modifier {
    val palette = LocalEzPalette.current
    return this.then(
        remember(palette, shape, strength) {
            Modifier.chromeShadow(shape, palette.glassShadow, 2.dp)
                .then(glassLayers(palette, shape, strength))
        },
    )
}

/**
 * The panel of a dialog, sheet, drawer or floating card: nearly opaque, with
 * a faint wash towards the bottom, a bright edge and a soft shadow, so it
 * reads as frosted glass over the dimmed screen and its text keeps AA contrast.
 */
@Composable
fun Modifier.glassPanel(shape: Shape, elevation: Dp = 18.dp): Modifier {
    val palette = LocalEzPalette.current
    return this.then(
        remember(palette, shape, elevation) {
            val fill = Brush.verticalGradient(listOf(palette.dialogSurface, palette.sheetSurface))
            Modifier
                .raised(shape, elevation, palette.glassShadow)
                .background(fill, shape)
                .background(glowBrush(palette), shape)
                .background(reflectionBrush(palette), shape)
                .border(1.dp, edgeBrush(palette), shape)
                .clip(shape)
        },
    )
}

private fun glassLayers(palette: EzPalette, shape: Shape, strength: Float): Modifier {
    val alpha = palette.glassAlpha(strength)
    val fill = Brush.verticalGradient(
        listOf(
            palette.glassTop.copy(alpha = alpha),
            palette.glassBottom.copy(alpha = (alpha * 0.92f).coerceAtMost(1f)),
        ),
    )
    return Modifier
        .background(fill, shape)
        .background(glowBrush(palette), shape)
        .background(reflectionBrush(palette), shape)
        .border(1.dp, edgeBrush(palette), shape)
        .clip(shape)
}

/** The inner glow: brightest under the top edge, gone by the middle. */
private fun glowBrush(palette: EzPalette) = Brush.verticalGradient(0f to palette.glassGlow, 0.45f to Color.Transparent)

/** A stationary diagonal reflection; cached with the surface, without blur or offscreen captures. */
private fun reflectionBrush(palette: EzPalette) = Brush.linearGradient(
    0f to Color.White.copy(alpha = if (palette.dark) 0.10f else 0.20f),
    0.32f to Color.Transparent,
    0.72f to Color.Transparent,
    1f to palette.glassGlow.copy(alpha = if (palette.dark) 0.025f else 0.06f),
)

/** The thin edge, bright along the top. */
private fun edgeBrush(palette: EzPalette) = Brush.verticalGradient(listOf(palette.glassEdgeTop, palette.glassEdgeBottom))

/** A low platform shadow instead of expanded filled outlines that form visible rings on paper. */
private fun Modifier.chromeShadow(shape: Shape, color: Color, elevation: Dp): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = color.copy(alpha = 0.06f),
    spotColor = color.copy(alpha = 0.10f),
)

/** Solid surface with a soft drop shadow: document pages, the primary button, panels. */
fun Modifier.raised(shape: Shape, elevation: Dp = 10.dp, tint: Color = ShadowInk): Modifier =
    shadow(elevation, shape, ambientColor = tint.copy(alpha = 0.18f), spotColor = tint.copy(alpha = 0.22f))

private val ShadowInk = Color(0xFF312038)

/** The brand's red gradient (active chip, primary button). */
val PrimaryGradient = Brush.linearGradient(listOf(Color(0xFFFF5B70), Color(0xFFF10A32), Color(0xFFC90023)))

/** The gloss on red: a bright upper half that fades out, like the mockup's buttons. */
private val PrimaryGloss = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.38f),
    0.48f to Color.White.copy(alpha = 0.10f),
    0.5f to Color.Transparent,
)
private val PrimaryEdge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f)))

/** Glossy red: the primary button, the selected chip, the floating Open file. Same in both themes. */
fun Modifier.glossyPrimary(shape: Shape, elevation: Dp = 12.dp): Modifier = this
    .raised(shape, elevation, EzColors.Primary)
    .background(PrimaryGradient, shape)
    .background(PrimaryGloss, shape)
    .border(1.dp, PrimaryEdge, shape)
    .clip(shape)

/**
 * Expressive spring feedback: shrinks slightly while pressed. The scale is
 * read inside the layer block, so pressing only redraws; it never recomposes.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val reduced = LocalReducedMotion.current
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = if (reduced) snap() else EzMotion.fastSpatial(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * A small expressive "pop" when something becomes selected or switched on: it
 * dips and springs back a touch past full size before settling. Nothing happens
 * on the first composition, so screens don't bounce as they appear.
 */
@Composable
fun Modifier.selectionPop(selected: Boolean, dip: Float = 0.9f): Modifier {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(selected) }
    LaunchedEffect(selected, reduced) {
        val justSelected = selected && !previous
        previous = selected
        if (reduced) {
            scale.snapTo(1f)
        } else {
            if (justSelected) scale.snapTo(dip)
            if (scale.value != 1f) scale.animateTo(1f, EzMotion.emphasis())
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Library background: the theme's gradient with red light and flowing ribbons.
 * Drawn once per size and theme into a bitmap, then blitted on each frame, so
 * scrolling never re-runs the gradients.
 */
@Composable
fun Modifier.libraryBackground(): Modifier {
    val palette = LocalEzPalette.current
    return this.then(
        remember(palette) {
            Modifier.drawWithCache {
                val image = rasterize {
                    val base = Brush.linearGradient(palette.waveBase, Offset.Zero, Offset(size.width, size.height))
                    val glowTop = Brush.radialGradient(
                        listOf(palette.waveGlowTop, Color.Transparent),
                        Offset(size.width * 0.85f, size.height * 0.08f),
                        size.width * 0.75f,
                    )
                    val glowBottom = Brush.radialGradient(
                        listOf(palette.waveGlowBottom, Color.Transparent),
                        Offset(size.width * 0.05f, size.height * 0.75f),
                        size.width * 0.9f,
                    )
                    drawRect(base)
                    drawRect(glowTop)
                    drawRect(glowBottom)
                    drawRibbon(palette, top = true)
                    drawRibbon(palette, top = false)
                }
                onDrawBehind { drawImage(image) }
            }
        },
    )
}

/** Reader background: calmer than the library's, so the page stands out. Cached per size and theme. */
@Composable
fun Modifier.readerBackground(): Modifier {
    val palette = LocalEzPalette.current
    return this.then(
        remember(palette) {
            Modifier.drawWithCache {
                val image = rasterize {
                    val base = Brush.verticalGradient(palette.readerBase)
                    val glow = Brush.radialGradient(
                        listOf(palette.readerGlow, Color.Transparent),
                        Offset(size.width * 0.85f, size.height * 0.2f),
                        size.width * 0.8f,
                    )
                    drawRect(base)
                    drawRect(glow)
                    drawRibbon(palette, top = false, alpha = 0.55f)
                }
                onDrawBehind { drawImage(image) }
            }
        },
    )
}

/** Draws [block] once into an offscreen bitmap the size of this node. */
private fun CacheDrawScope.rasterize(block: DrawScope.() -> Unit): ImageBitmap {
    val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(this, layoutDirection, Canvas(bitmap), size, block)
    return bitmap
}

/**
 * A flowing ribbon across the top right or the bottom: a filled wave with a
 * glossy crest line along its edge, as in the mockup.
 */
private fun DrawScope.drawRibbon(palette: EzPalette, top: Boolean, alpha: Float = 1f) {
    val w = size.width
    val h = size.height
    val edge = Path()
    val path = Path().apply {
        if (top) {
            moveTo(w * 0.35f, 0f)
            cubicTo(w * 0.55f, h * 0.10f, w * 0.75f, h * 0.02f, w, h * 0.12f)
            lineTo(w, 0f)
            close()
            edge.moveTo(w * 0.35f, 0f)
            edge.cubicTo(w * 0.55f, h * 0.10f, w * 0.75f, h * 0.02f, w, h * 0.12f)
        } else {
            moveTo(0f, h * 0.70f)
            cubicTo(w * 0.30f, h * 0.62f, w * 0.55f, h * 0.92f, w, h * 0.80f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
            edge.moveTo(0f, h * 0.70f)
            edge.cubicTo(w * 0.30f, h * 0.62f, w * 0.55f, h * 0.92f, w, h * 0.80f)
        }
    }
    val colors = if (top) palette.ribbonTop else palette.ribbonBottom
    drawPath(path, Brush.linearGradient(colors, Offset(0f, h * 0.6f), Offset(w, h)), alpha = alpha)
    // The crest: a thin bright line that fades along the ribbon.
    val crest = Brush.horizontalGradient(
        listOf(Color.Transparent, palette.glassEdgeTop.copy(alpha = palette.glassEdgeTop.alpha * 0.7f), Color.Transparent),
    )
    drawPath(edge, crest, alpha = alpha, style = Stroke(width = 1.5.dp.toPx()))
}

@Composable
fun Modifier.glassScreen(): Modifier {
    val palette = LocalEzPalette.current
    return drawWithCache {
        val tint = palette.glassTop.copy(alpha = if (palette.dark) 0.22f else 0.36f)
        val reflection = Brush.linearGradient(
            0f to tint,
            0.34f to tint.copy(alpha = tint.alpha * 0.36f),
            0.70f to Color.Transparent,
            1f to palette.glassBottom.copy(alpha = if (palette.dark) 0.12f else 0.22f),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        )
        val topLight = Brush.verticalGradient(
            0f to palette.glassGlow.copy(alpha = if (palette.dark) 0.07f else 0.26f),
            1f to Color.Transparent,
            endY = (size.height * 0.36f).coerceAtLeast(1f),
        )
        onDrawBehind {
            drawRect(reflection)
            drawRect(topLight)
        }
    }
}
