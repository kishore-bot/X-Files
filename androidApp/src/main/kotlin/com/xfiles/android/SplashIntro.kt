package com.xfiles.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The space the splash mark occupies above the wordmark, so the lockup sits under the icon's footprint. */
private val MarkGap = 200.dp

/**
 * The second half of the cold-start sequence, over the app's first frame while the system splash's exit runs
 * (see [StartupGate.beginExit]). The layer carries the lockup under the mark: the wordmark "Xfiles" and the tagline
 * "Premium feel. Faster app.", both centred. Its timeline is the exit's length [StartupGate.exitMs]:
 *  - 0 to 50%: the wordmark rises and fades in, tracking tightening into place.
 *  - 50 to 80%: the tagline fades in.
 *  - 80 to 100%: the whole layer fades and scales up into the app.
 * A tap or [StartupGate.skipIntro] ends it at once. Shown on cold start only (see MainActivity).
 */
@Composable
fun SplashIntro(modifier: Modifier = Modifier, onFinished: () -> Unit) {
    val exitMs by StartupGate.exitMs.collectAsState()
    val skipped by StartupGate.skipped.collectAsState()
    val nameAlpha = remember { Animatable(0f) }
    val nameShift = remember { Animatable(12f) }
    val nameTracking = remember { Animatable(0.6f) }
    val tagAlpha = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val done by rememberUpdatedState(onFinished)

    LaunchedEffect(exitMs, skipped) {
        if (skipped) {
            exit.snapTo(0f)
            done()
            return@LaunchedEffect
        }
        if (exitMs <= 0) return@LaunchedEffect
        val total = exitMs
        val half = total / 2
        coroutineScope {
            launch { nameAlpha.animateTo(1f, tween(half, easing = FastOutSlowInEasing)) }
            launch { nameShift.animateTo(0f, tween(half, easing = FastOutSlowInEasing)) }
            launch { nameTracking.animateTo(0f, tween(half, easing = FastOutSlowInEasing)) }
            launch {
                delay(half.toLong())
                tagAlpha.animateTo(1f, tween(total * 3 / 10, easing = LinearEasing))
            }
        }
        // The whole lockup, then the layer, fades and scales into the app.
        exit.animateTo(0f, tween(total / 5, easing = FastOutSlowInEasing))
        done()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(EzColors.Background)
            .graphicsLayer {
                alpha = exit.value
                val s = 1f + 0.06f * (1f - exit.value)
                scaleX = s
                scaleY = s
            }
            .clickable(enabled = exitMs > 0) { StartupGate.skipIntro() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = MarkGap / 2),
        ) {
            Text(
                "Xfiles",
                modifier = Modifier
                    .alpha(nameAlpha.value)
                    .offset(y = nameShift.value.dp),
                color = EzColors.Text,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = nameTracking.value.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Premium feel. Faster app.",
                modifier = Modifier.alpha(tagAlpha.value),
                color = EzColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
            )
        }
    }
}
