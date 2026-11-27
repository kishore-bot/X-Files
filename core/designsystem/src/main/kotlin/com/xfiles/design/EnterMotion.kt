package com.xfiles.design

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Fades and lifts an item in when it first appears, staggered by [index] (24 ms apart, capped so long lists
 * do not wait). Off when the system asks for reduced motion. Draws only the layer; no layout work per frame.
 */
@Composable
fun Modifier.staggeredEnter(index: Int): Modifier {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (reduced) {
            progress.snapTo(1f)
        } else if (progress.value < 1f) {
            delay(index.coerceIn(0, 4) * 24L)
            progress.animateTo(1f, EzMotion.defaultEffects())
        }
    }
    val lift = with(LocalDensity.current) { 6.dp.toPx() }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * lift
    }
}
