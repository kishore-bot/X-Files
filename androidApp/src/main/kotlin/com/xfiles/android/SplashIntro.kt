package com.xfiles.android

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors

/**
 * A short branded intro over the first frame: the mark scales in from a soft glass bloom (blurred on Android 12+),
 * then "Xfiles" fades up. About 900 ms in total; [onFinished] fires once it ends. Shown on cold start only.
 */
@Composable
fun SplashIntro(modifier: Modifier = Modifier, onFinished: () -> Unit) {
    val logoScale = remember { Animatable(0.72f) }
    val logoAlpha = remember { Animatable(0f) }
    val bloom = remember { Animatable(0f) }
    val nameAlpha = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val done by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        // Bloom and logo rise together (0-420 ms), name reveals (360-700 ms), then the whole intro fades (700-900 ms).
        bloom.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        logoScale.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        logoAlpha.animateTo(1f, tween(260, easing = LinearEasing))
        nameAlpha.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
        exit.animateTo(0f, tween(200, easing = LinearEasing))
        done()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(EzColors.Background)
            .alpha(exit.value),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // The glass bloom behind the mark: blurred on Android 12+, a soft radial tint below that.
                Box(
                    Modifier
                        .size(190.dp)
                        .scale(0.8f + 0.25f * bloom.value)
                        .alpha(0.55f * bloom.value)
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier.alpha(0.3f * bloom.value))
                        .background(EzColors.Accent.copy(alpha = 0.35f), CircleShape),
                )
                Image(
                    painter = painterResource(R.drawable.ic_splash_mark),
                    contentDescription = null,
                    modifier = Modifier
                        .size(116.dp)
                        .scale(logoScale.value)
                        .alpha(logoAlpha.value),
                )
            }
            Text(
                "Xfiles",
                modifier = Modifier.alpha(nameAlpha.value),
                color = EzColors.Text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
