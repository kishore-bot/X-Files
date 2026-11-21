package com.xfiles.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Fily's pose. The assistant maps its own state onto these; Happy is for a moment of success. */
enum class FilyMascotState { Idle, Listening, Thinking, Speaking, Happy }

/**
 * Fily, the holographic globe robot, drawn as vectors from the owner's SVG (viewBox 200x185).
 * Animation runs through draw-phase reads only, so a frame never recomposes. With Remove animations on
 * ([LocalReducedMotion]) Fily stands still in its idle pose.
 */
@Composable
fun FilyMascot(
    modifier: Modifier = Modifier,
    state: FilyMascotState = FilyMascotState.Idle,
    size: Dp = 190.dp,
) {
    val reduced = LocalReducedMotion.current
    val clock = if (reduced) remember { stillClock() } else rememberLiveClock()
    val weights = rememberWeights(state, reduced)
    Spacer(
        modifier.size(size).drawWithCache {
            val geometry = MascotGeometry(this.size.width, this.size.height)
            onDrawBehind { drawFily(geometry, clock, weights) }
        },
    )
}

/** The Fily prompt for an empty search: mascot, title, subtitle and the "Ask Fily" pill. */
@Composable
fun FilyMascotCallToAction(onAsk: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilyMascot(size = 160.dp)
        Text(
            "AI assistant Fily is here!",
            style = MaterialTheme.typography.titleMedium.copy(
                shadow = Shadow(color = Color(0xFF38E8FF).copy(alpha = 0.55f), blurRadius = 16f),
            ),
            color = Color(0xFF93C5FD),
            textAlign = TextAlign.Center,
        )
        Text(
            "No matching files? Let me help you find it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.78f),
            textAlign = TextAlign.Center,
        )
        FilyStatusPill(
            text = "Ask Fily · speak now",
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onAsk),
        )
    }
}

/** The dark glass status pill from the HTML overlay, with a pulsing red dot. */
@Composable
fun FilyStatusPill(text: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "fily-pill")
    val dot = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pill-dot",
    )
    Row(
        modifier
            .clip(CircleShape)
            .background(PillGlass, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.size(8.dp).drawBehind { drawCircle(PinkRed, alpha = 0.4f + 0.6f * dot.value) })
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

// ---- Geometry, built once per size (drawWithCache) ------------------------------------------------

private const val VIEW_W = 200f
private const val VIEW_H = 185f
private const val TWO_PI = 6.2831855f

private val Cyan = Color(0xFF38E8FF)
private val CyanSoft = Color(0xFF67E8F9)
private val CyanGlobe = Color(0xFF8BE9FD)
private val SkyRim = Color(0xFF38BDF8)
private val Blue = Color(0xFF2563EB)
private val Violet = Color(0xFF4C1D95)
private val Navy = Color(0xFF1E1B4B)
private val Visor = Color(0xFF090D1E)
private val Purple = Color(0xFFA855F7)
private val Pink = Color(0xFFF472B6)
private val Lilac = Color(0xFFC084FC)
private val Fuchsia = Color(0xFFE879F9)
private val PillGlass = Color(0xBF1C1018)
private val PinkRed = Color(0xFFF43F5E)

private class MascotGeometry(width: Float, height: Float) {
    val scale = min(width / VIEW_W, height / VIEW_H)
    val originX = (width - VIEW_W * scale) / 2f
    val originY = (height - VIEW_H * scale) / 2f
    val center = Offset(width / 2f, height / 2f)
    val auraRadius = min(width, height) / 2f

    /** Soft falloff instead of a blur: the aura's radial stops. */
    val aura = Brush.radialGradient(
        *arrayOf(
            0f to Cyan.copy(alpha = 0.35f),
            0.55f to Purple.copy(alpha = 0.2f),
            0.75f to Color.Transparent,
        ),
        center = center,
        radius = auraRadius,
    )

    // Brushes below use viewBox units; they are drawn under the viewBox transform.
    val globe = Brush.radialGradient(
        *arrayOf(0f to CyanGlobe, 0.45f to Blue, 0.85f to Violet, 1f to Navy),
        center = Offset(90.9f, 74.8f),
        radius = 51.7f,
    )
    val rim = Brush.linearGradient(
        *arrayOf(0f to CyanSoft.copy(alpha = 0.95f), 0.5f to Purple.copy(alpha = 0.6f), 1f to SkyRim.copy(alpha = 0.95f)),
        start = Offset(62f, 90f),
        end = Offset(138f, 90f),
    )
    val wingLeftBrush = Brush.linearGradient(
        *arrayOf(0f to Pink.copy(alpha = 0.9f), 1f to Cyan.copy(alpha = 0.85f)),
        start = Offset(32f, 86f),
        end = Offset(62f, 86f),
    )
    val wingRightBrush = Brush.linearGradient(
        *arrayOf(0f to Pink.copy(alpha = 0.9f), 1f to Cyan.copy(alpha = 0.85f)),
        start = Offset(138f, 86f),
        end = Offset(168f, 86f),
    )
    val beamBrush = Brush.linearGradient(
        *arrayOf(0f to Purple.copy(alpha = 0f), 1f to Cyan.copy(alpha = 0.45f)),
        start = Offset(100f, 104f),
        end = Offset(100f, 156f),
    )

    val basePoly = Path().apply { moveTo(68f, 156f); lineTo(132f, 156f); lineTo(146f, 115f); lineTo(54f, 115f); close() }
    val beamPoly = Path().apply { moveTo(82f, 104f); lineTo(118f, 104f); lineTo(134f, 156f); lineTo(66f, 156f); close() }
    val wingLeftOuter = Path().apply {
        moveTo(62f, 86f); cubicTo(42f, 72f, 32f, 76f, 36f, 86f); cubicTo(39f, 92f, 50f, 93f, 62f, 91f); close()
    }
    val wingLeftInner = Path().apply {
        moveTo(62f, 92f); cubicTo(46f, 92f, 40f, 98f, 45f, 103f); cubicTo(49f, 106f, 56f, 101f, 63f, 96f); close()
    }
    val wingRightOuter = Path().apply {
        moveTo(138f, 86f); cubicTo(158f, 72f, 168f, 76f, 164f, 86f); cubicTo(161f, 92f, 150f, 93f, 138f, 91f); close()
    }
    val wingRightInner = Path().apply {
        moveTo(138f, 92f); cubicTo(154f, 92f, 160f, 98f, 155f, 103f); cubicTo(151f, 106f, 144f, 101f, 137f, 96f); close()
    }
    val antenna = Path().apply { moveTo(76f, 58f); quadraticBezierTo(100f, 44f, 124f, 58f) }
    val eyeLeftArc = Path().apply { moveTo(83f, 89f); quadraticBezierTo(87.5f, 83f, 92f, 89f) }
    val eyeRightArc = Path().apply { moveTo(108f, 89f); quadraticBezierTo(112.5f, 83f, 117f, 89f) }
    val smile = Path().apply { moveTo(95f, 94f); quadraticBezierTo(100f, 99f, 105f, 94f) }
    val mouthBowl = Path().apply {
        moveTo(93.5f, 94f); quadraticBezierTo(100f, 97f, 106.5f, 94f); quadraticBezierTo(100f, 106f, 93.5f, 94f); close()
    }

    val thin = Stroke(1f)
    val rimStroke = Stroke(2.2f)
    val visorStroke = Stroke(1.4f)
    val earStroke = Stroke(1.6f)
    val antennaStroke = Stroke(2f, cap = StrokeCap.Round)
    val antennaGlow = Stroke(6f, cap = StrokeCap.Round)
    val eyeGlow = Stroke(7.5f, cap = StrokeCap.Round)
    val eyeStroke = Stroke(2.6f, cap = StrokeCap.Round)
    val smileStroke = Stroke(2.2f, cap = StrokeCap.Round)
    val bowlStroke = Stroke(1.6f, cap = StrokeCap.Round)
    val waveStroke = Stroke(1.4f, cap = StrokeCap.Round)
    val baseStroke = Stroke(2f)
    val baseGlow = Stroke(6f)
    val innerRing = Stroke(1.5f)
    val hairline = Stroke(1f)
}

// ---- Motion: a clock of looping phases and a set of state weights, all read in the draw phase -------

private class MascotClock(
    val bob: State<Float>,
    val flap: State<Float>,
    val flapFast: State<Float>,
    val blink: State<Float>,
    val pulse: State<Float>,
    val aura: State<Float>,
    val orbit: State<Float>,
    val wave: State<Float>,
    val mic: State<Float>,
    val mouth: State<Float>,
    val mouthFast: State<Float>,
    val hop: State<Float>,
)

private class MascotWeights(
    val listen: State<Float>,
    val think: State<Float>,
    val speak: State<Float>,
    val happy: State<Float>,
    val fastFlap: State<Float>,
)

private fun stillClock(): MascotClock {
    fun still(v: Float): State<Float> = mutableFloatStateOf(v)
    return MascotClock(
        bob = still(0f), flap = still(0.5f), flapFast = still(0.5f), blink = still(1f), pulse = still(0f),
        aura = still(0.5f), orbit = still(0f), wave = still(0f), mic = still(0f), mouth = still(0f),
        mouthFast = still(0f), hop = still(0f),
    )
}

@Composable
private fun rememberLiveClock(): MascotClock {
    val t = rememberInfiniteTransition(label = "fily")
    return MascotClock(
        bob = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob"),
        flap = t.animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "flap"),
        flapFast = t.animateFloat(0f, 1f, infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "flapFast"),
        blink = t.animateFloat(
            1f,
            1f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = 4500
                    1f at 0
                    1f at 4050
                    0.1f at 4275
                    1f at 4500
                },
            ),
            label = "blink",
        ),
        pulse = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"),
        aura = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "aura"),
        orbit = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "orbit"),
        wave = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "wave"),
        mic = t.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mic"),
        mouth = t.animateFloat(0f, 1f, infiniteRepeatable(tween(330, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mouth"),
        mouthFast = t.animateFloat(0f, 1f, infiniteRepeatable(tween(120, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mouthFast"),
        hop = t.animateFloat(0f, 1f, infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "hop"),
    )
}

@Composable
private fun rememberWeights(state: FilyMascotState, reduced: Boolean): MascotWeights {
    val spec: AnimationSpec<Float> = if (reduced) snap<Float>() else spring<Float>(dampingRatio = 0.8f, stiffness = 260f)
    return MascotWeights(
        listen = animateFloatAsState(if (state == FilyMascotState.Listening) 1f else 0f, spec, label = "listen"),
        think = animateFloatAsState(if (state == FilyMascotState.Thinking) 1f else 0f, spec, label = "think"),
        speak = animateFloatAsState(if (state == FilyMascotState.Speaking) 1f else 0f, spec, label = "speak"),
        happy = animateFloatAsState(if (state == FilyMascotState.Happy) 1f else 0f, spec, label = "happy"),
        fastFlap = animateFloatAsState(if (state == FilyMascotState.Listening) 1f else 0f, spec, label = "fastFlap"),
    )
}

// ---- Drawing: viewBox coordinates, scaled and centred into the frame -------------------------------

private fun DrawScope.drawFily(g: MascotGeometry, c: MascotClock, w: MascotWeights) {
    val aura = c.aura.value
    val auraScale = 1f + 0.08f * aura
    withTransform({ scale(auraScale, auraScale, pivot = g.center) }) {
        drawCircle(brush = g.aura, radius = g.auraRadius, center = g.center, alpha = 0.75f + 0.25f * aura)
    }
    withTransform({
        translate(g.originX, g.originY)
        scale(g.scale, g.scale, pivot = Offset.Zero)
    }) {
        drawBase(g, c.pulse.value)
        drawBody(g, c, w)
    }
}

private fun DrawScope.drawBase(g: MascotGeometry, pulse: Float) {
    val k = 1f - 0.08f * pulse
    val ring = 1f - 0.4f * pulse
    withTransform({ scale(k, k, pivot = Offset(100f, 158f)) }) {
        drawPath(g.basePoly, Visor, alpha = 0.9f)
        drawPath(g.basePoly, Cyan, alpha = 0.25f, style = g.hairline)
        drawPath(g.beamPoly, g.beamBrush, alpha = ring)
        drawOval(Cyan, topLeft = Offset(66f, 150f), size = Size(68f, 16f), alpha = 0.12f * ring)
        drawOval(Cyan, topLeft = Offset(66f, 150f), size = Size(68f, 16f), alpha = 0.3f * ring, style = g.baseGlow)
        drawOval(Cyan, topLeft = Offset(66f, 150f), size = Size(68f, 16f), alpha = ring, style = g.baseStroke)
        drawOval(Lilac, topLeft = Offset(78f, 153.5f), size = Size(44f, 9f), alpha = ring, style = g.innerRing)
    }
}

private fun DrawScope.drawBody(g: MascotGeometry, c: MascotClock, w: MascotWeights) {
    val listen = w.listen.value
    val think = w.think.value
    val speak = w.speak.value
    val happy = w.happy.value
    val pulse = c.pulse.value
    val floatY = -7f * c.bob.value - 9f * c.hop.value * happy
    withTransform({ translate(0f, floatY) }) {
        drawWings(g, c, w)
        drawCircle(brush = g.globe, radius = 38f, center = Offset(100f, 90f))
        drawCircle(brush = g.rim, radius = 38f, center = Offset(100f, 90f), style = g.rimStroke)
        drawOval(CyanSoft, topLeft = Offset(62f, 77f), size = Size(76f, 26f), alpha = 0.28f, style = g.thin)
        drawOval(CyanSoft, topLeft = Offset(84f, 52f), size = Size(32f, 76f), alpha = 0.25f, style = g.thin)

        drawPath(g.antenna, CyanSoft, alpha = 0.35f, style = g.antennaGlow)
        drawPath(g.antenna, CyanSoft, style = g.antennaStroke)
        drawCircle(Cyan, radius = 3f, center = Offset(75f, 59f))
        drawCircle(Cyan, radius = 3f, center = Offset(125f, 59f))

        drawRoundRect(Navy, topLeft = Offset(57f, 81f), size = Size(7f, 18f), cornerRadius = CornerRadius(3.5f))
        drawRoundRect(Cyan, topLeft = Offset(57f, 81f), size = Size(7f, 18f), cornerRadius = CornerRadius(3.5f), style = g.earStroke)
        drawRoundRect(Navy, topLeft = Offset(136f, 81f), size = Size(7f, 18f), cornerRadius = CornerRadius(3.5f))
        drawRoundRect(Cyan, topLeft = Offset(136f, 81f), size = Size(7f, 18f), cornerRadius = CornerRadius(3.5f), style = g.earStroke)

        drawRoundRect(Visor, topLeft = Offset(73f, 75f), size = Size(54f, 30f), cornerRadius = CornerRadius(15f))
        drawRoundRect(Cyan, topLeft = Offset(73f, 75f), size = Size(54f, 30f), cornerRadius = CornerRadius(15f), alpha = 0.75f, style = g.visorStroke)

        withTransform({ translate(0f, -2.5f * think) }) {
            val blink = c.blink.value
            drawEye(g, g.eyeLeftArc, 87.5f, listen, blink)
            drawEye(g, g.eyeRightArc, 112.5f, listen, blink)
        }

        drawOval(Pink, topLeft = Offset(78.8f, 92.2f), size = Size(6.4f, 3.6f), alpha = 0.8f)
        drawOval(Pink, topLeft = Offset(114.8f, 92.2f), size = Size(6.4f, 3.6f), alpha = 0.8f)

        val level = 0.5f * c.mouth.value + 0.5f * c.mouthFast.value
        withTransform({ scale(1f + 0.2f * happy, 1f, pivot = Offset(100f, 94f)) }) {
            drawPath(g.smile, CyanSoft, alpha = 1f - speak, style = g.smileStroke)
            if (speak > 0f) {
                withTransform({ scale(1f, 0.2f + 1.2f * level, pivot = Offset(100f, 94f)) }) {
                    drawPath(g.mouthBowl, Navy, alpha = 0.9f * speak)
                    drawPath(g.mouthBowl, CyanSoft, alpha = speak, style = g.bowlStroke)
                }
            }
        }

        drawCircle(CyanSoft, radius = 7f, center = Offset(100f, 117f), alpha = 0.25f)
        drawCircle(CyanSoft, radius = 4.5f, center = Offset(100f, 117f))

        val twinkle = 0.55f + 0.45f * pulse
        drawCircle(CyanSoft, radius = 1.8f, center = Offset(52f, 64f), alpha = twinkle)
        drawCircle(Fuchsia, radius = 2f, center = Offset(148f, 66f), alpha = twinkle)
        drawCircle(Cyan, radius = 1.5f, center = Offset(144f, 118f), alpha = twinkle)
        drawCircle(Lilac, radius = 1.5f, center = Offset(56f, 116f), alpha = twinkle)

        if (listen > 0f) drawListening(g, c, listen)
        if (think > 0f) drawThinking(c, think)
    }
}

private fun DrawScope.drawWings(g: MascotGeometry, c: MascotClock, w: MascotWeights) {
    val slow = 10f * (2f * c.flap.value - 1f)
    val fast = 12f * (2f * c.flapFast.value - 1f)
    val angle = slow + (fast - slow) * w.fastFlap.value
    withTransform({ rotate(angle, pivot = Offset(62f, 86f)) }) {
        drawPath(g.wingLeftOuter, g.wingLeftBrush)
        drawPath(g.wingLeftInner, Cyan, alpha = 0.85f)
    }
    withTransform({ rotate(-angle, pivot = Offset(138f, 86f)) }) {
        drawPath(g.wingRightOuter, g.wingRightBrush)
        drawPath(g.wingRightInner, Cyan, alpha = 0.85f)
    }
}

/** One eye: the happy arc, or the round open eye when listening. The blink squashes it about its centre. */
private fun DrawScope.drawEye(g: MascotGeometry, arc: Path, cx: Float, listen: Float, blink: Float) {
    withTransform({ scale(1f, blink, pivot = Offset(cx, 88f)) }) {
        if (listen < 1f) {
            drawPath(arc, Cyan, alpha = (1f - listen) * 0.25f, style = g.eyeGlow)
            drawPath(arc, Cyan, alpha = 1f - listen, style = g.eyeStroke)
        }
        if (listen > 0f) {
            drawOval(Cyan, topLeft = Offset(cx - 3.6f, 83.4f), size = Size(7.2f, 9.2f), alpha = listen * 0.95f)
            drawCircle(Color.White, radius = 1.1f, center = Offset(cx + 1.2f, 85.5f), alpha = listen * 0.9f)
        }
    }
}

private fun DrawScope.drawListening(g: MascotGeometry, c: MascotClock, listen: Float) {
    val w = c.wave.value
    for (k in 0 until 3) {
        val p = (w + k / 3f) % 1f
        val r = 14f + 24f * p
        val a = listen * (1f - p) * 0.9f
        drawArc(
            CyanSoft, startAngle = 135f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(60.5f - r, 90f - r), size = Size(2f * r, 2f * r), alpha = a, style = g.waveStroke,
        )
        drawArc(
            CyanSoft, startAngle = -45f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(139.5f - r, 90f - r), size = Size(2f * r, 2f * r), alpha = a, style = g.waveStroke,
        )
    }
    val mic = c.mic.value
    drawCircle(Cyan, radius = 2.6f * (1f - 0.2f * mic), center = Offset(100f, 34f), alpha = listen * (1f - 0.6f * mic))
}

private fun DrawScope.drawThinking(c: MascotClock, think: Float) {
    val o = c.orbit.value
    for (i in 0 until 3) {
        val theta = TWO_PI * (o + i / 3f)
        val depth = 0.5f + 0.5f * sin(theta)
        drawCircle(
            Fuchsia,
            radius = 2f + 0.8f * depth,
            center = Offset(100f + 58f * cos(theta), 90f + 22f * sin(theta)),
            alpha = think * (0.4f + 0.6f * depth),
        )
    }
}

