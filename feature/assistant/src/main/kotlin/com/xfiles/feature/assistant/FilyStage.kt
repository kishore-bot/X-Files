package com.xfiles.feature.assistant

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * The stage Fily hovers over, as in EzFiles: a soft pink-blue haze behind the robot and a glowing
 * horizon arc below it. Paths and brushes are built once per size; [time] and [reveal] are read in
 * the draw phase only, so animating them never recomposes.
 */
@Composable
internal fun FilyStage(time: State<Float>, reveal: State<Float>, modifier: Modifier = Modifier, height: Dp = 270.dp) {
    Spacer(
        modifier.fillMaxWidth().height(height).drawWithCache {
            // Haze.
            val hazeW = 320.dp.toPx()
            val hazeH = 200.dp.toPx()
            val hazeCenter = Offset(size.width / 2, 20.dp.toPx() + hazeH / 2)
            val haze = Brush.radialGradient(
                0f to Color(0x25BD2CAA), 0.36f to Color(0x17495DFF), 0.7f to Color.Transparent,
                center = hazeCenter, radius = hazeW / 2,
            )
            // Horizon.
            val hw = max(size.width, 530.dp.toPx())
            val hh = 133.dp.toPx()
            val left = (size.width - hw) / 2
            val top = size.height - 7.dp.toPx() - hh
            fun x(v: Float) = left + v / 440f * hw
            fun y(v: Float) = top + v / 133f * hh
            fun arc(control: Float) = Path().apply {
                moveTo(x(-35f), y(133f))
                cubicTo(x(95f), y(control), x(315f), y(control), x(475f), y(133f))
            }
            val main = arc(52f)
            val inner = arc(62f)
            val brush = Brush.horizontalGradient(
                0f to Color(0x40F72C92), 0.27f to Color(0xF2C457FD), 0.51f to Color(0xFFFF75E8),
                0.67f to Color(0xFF528BFF), 1f to Color(0x4DED418C),
                startX = left, endX = left + hw,
            )
            val glow = Stroke(18f / 133f * hh)
            val line = Stroke(2.dp.toPx())
            val thin = Stroke(0.7.dp.toPx())
            onDrawBehind {
                val shown = reveal.value
                if (shown <= 0f) return@onDrawBehind
                scale(1f, hazeH / hazeW, pivot = hazeCenter) {
                    drawCircle(haze, radius = hazeW / 2 * (0.6f + 0.4f * shown), center = hazeCenter, alpha = shown)
                }
                val pulse = (0.7f + 0.3f * (1f - cos(2f * PI.toFloat() * time.value / 4f)) / 2f) * shown
                // The horizon opens out from the middle as Fily arrives.
                scale(0.3f + 0.7f * shown, 1f, pivot = Offset(size.width / 2, size.height)) {
                    drawPath(main, brush, alpha = 0.13f * pulse, style = glow)
                    drawPath(main, brush, alpha = pulse, style = line)
                    drawPath(inner, brush, alpha = 0.7f * pulse, style = thin)
                }
            }
        },
    )
}
