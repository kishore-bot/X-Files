package com.xfiles.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors

/** Turntable speed of the gradient disc, in degrees per second while a track plays. */
private const val DISC_DEGREES_PER_SECOND = 24f

/**
 * The stage for an audio file: its embedded cover art, blurred behind and shown large, or a gradient disc when the
 * file has no picture. Title, artist and album sit under the art.
 */
@Composable
internal fun MediaAudioStage(
    title: String,
    artist: String?,
    album: String?,
    artwork: Bitmap?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(RADIUS_BACKDROP),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.92f)))),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(EzColors.Primary.copy(alpha = 0.5f), Color(0xFF120B14))),
                    ),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(ART_SIZE)
                        .shadow(24.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp)),
                )
            } else {
                SpinningDisc(playing, Modifier.size(ART_SIZE))
            }
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 28.dp).fillMaxWidth(),
            )
            if (artist != null) {
                Text(
                    artist,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                )
            }
            if (album != null) {
                Text(
                    album,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** A record drawn in code: a sweep of the accent colour, grooves, and a label. It turns only while playing. */
@Composable
private fun SpinningDisc(playing: Boolean, modifier: Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                angle = (angle + (now - last) / 1_000_000_000f * DISC_DEGREES_PER_SECOND) % 360f
                last = now
            }
        }
    }
    Canvas(modifier.graphicsLayer { rotationZ = angle }) {
        val radius = size.minDimension / 2f
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(Color(0xFF2A2433), EzColors.Primary.copy(alpha = 0.9f), Color(0xFF2A2433), Color(0xFF15111C), Color(0xFF2A2433)),
            ),
            radius = radius,
        )
        for (ring in 1..5) {
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = radius * (0.45f + ring * 0.09f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        drawCircle(color = Color(0xFF0B0A0F), radius = radius * 0.2f)
        drawCircle(color = Color.White.copy(alpha = 0.18f), radius = radius * 0.2f, style = Stroke(width = 1.dp.toPx()))
    }
}

private val ART_SIZE = 268.dp
private val RADIUS_BACKDROP = 48.dp
