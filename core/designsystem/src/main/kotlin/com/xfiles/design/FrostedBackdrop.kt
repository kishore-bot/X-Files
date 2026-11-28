package com.xfiles.design

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
fun rememberFrostedBackdrop(): FrostedBackdrop {
    val state = rememberHazeState()
    return remember(state) { FrostedBackdrop(state) }
}

/** Draw the source behind a sibling control so its surface can sample the live backdrop. */
class FrostedBackdrop internal constructor(private val state: HazeState) {
    fun source(modifier: Modifier): Modifier =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) modifier.hazeSource(state) else modifier

    @Composable
    fun blur(modifier: Modifier, shape: Shape): Modifier {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return modifier
        val background = LocalEzPalette.current.background
        val style = remember(background) {
            HazeBlurStyle {
                backgroundColor(background)
                blurRadius(16.dp)
                noiseFactor(0f)
                colorEffects(emptyList())
            }
        }
        return modifier.clip(shape).hazeBlur(input = HazeInput.Sources(state), style = style)
    }
}
