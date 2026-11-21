package com.xfiles.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.xfiles.core.documents.PdfMatchBox

@Composable
internal fun ReaderImage(
    bitmap: Bitmap,
    description: String,
    rotation: Int,
    highlights: List<PdfMatchBox> = emptyList(),
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(bitmap, rotation) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap, rotation) { mutableStateOf(Offset.Zero) }

    val turned = rotation % 180 != 0
    val width = if (turned) bitmap.height else bitmap.width
    val height = if (turned) bitmap.width else bitmap.height
    val renderScale = fitScale(viewport, width, height)
    val originalScale = fitScale(viewport, bitmap.width, bitmap.height)
    val rotatedScale = if (originalScale > 0f) renderScale / originalScale else 1f

    fun clamp(pan: Offset, scale: Float): Offset {
        val horizontal = ((width * renderScale * scale - viewport.width) / 2).coerceAtLeast(0f)
        val vertical = ((height * renderScale * scale - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(pan.x.coerceIn(-horizontal, horizontal), pan.y.coerceIn(-vertical, vertical))
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
            .pointerInput(bitmap, rotation, viewport) {
                detectTransformGestures(panZoomLock = true) { centroid, pan, change, _ ->
                    val previous = zoom
                    val updated = (previous * change).coerceIn(1f, 5f)
                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                    val focus = centroid - center
                    val nextOffset = focus + (offset - focus) * (updated / previous) + pan
                    zoom = updated
                    offset = clamp(nextOffset, updated)
                }
            }
            .pointerInput(bitmap, rotation, viewport) {
                detectTapGestures(onDoubleTap = {
                    zoom = if (zoom == 1f) 2.5f else 1f
                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                    offset = clamp((center - it) * (zoom - 1f), zoom)
                })
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                rotationZ = rotation.toFloat()
                scaleX = rotatedScale * zoom
                scaleY = rotatedScale * zoom
                translationX = offset.x
                translationY = offset.y
            },
        ) {
            Image(image, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (highlights.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize()) {
                    val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    val left = (size.width - width) / 2
                    val top = (size.height - height) / 2
                    for (box in highlights) {
                        if (box.right <= box.left || box.bottom <= box.top) continue
                        drawRect(
                            Color(0xFFFFC928).copy(alpha = 0.42f),
                            topLeft = Offset(left + box.left * width, top + box.top * height),
                            size = Size((box.right - box.left) * width, (box.bottom - box.top) * height),
                        )
                    }
                }
            }
        }
    }
}

private fun fitScale(container: IntSize, width: Int, height: Int): Float =
    minOf(container.width.toFloat() / width, container.height.toFloat() / height)
