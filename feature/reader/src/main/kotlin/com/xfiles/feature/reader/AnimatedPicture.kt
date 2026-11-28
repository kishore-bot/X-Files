package com.xfiles.feature.reader

import android.graphics.drawable.AnimatedImageDrawable
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Plays an animated picture (GIF, WebP, AVIF) fitted like a still one. [playing] is true only for the settled page:
 * off-screen pages stop, and the frame they stop on stays until they play again. With the system's reduced motion
 * (animator duration scale 0) the picture never starts and shows its first frame. The View keeps the drawable as
 * its content, so the enclosing zoom layer scales the animation like the still image.
 */
@Composable
internal fun AnimatedPicture(drawable: AnimatedImageDrawable, playing: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val resolver = LocalContext.current.contentResolver
    val reducedMotion = remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    LaunchedEffect(drawable, playing, reducedMotion) {
        if (playing && !reducedMotion) drawable.start() else drawable.stop()
    }
    DisposableEffect(drawable) {
        onDispose { drawable.stop() }
    }
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(drawable)
            }
        },
        update = { view -> if (view.drawable !== drawable) view.setImageDrawable(drawable) },
        modifier = modifier,
    )
}
