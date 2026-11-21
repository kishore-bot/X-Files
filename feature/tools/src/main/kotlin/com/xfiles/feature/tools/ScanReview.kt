package com.xfiles.feature.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.exifinterface.media.ExifInterface
import com.xfiles.core.documents.ScanFilter
import com.xfiles.core.documents.ScanProcessing
import com.xfiles.design.EzColors
import com.xfiles.scan.PageQuad
import com.xfiles.scan.ScanPoint
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Corners used when detection found no page: a visible inset of the photo. */
private val InsetPage = PageQuad(ScanPoint(0.08f, 0.08f), ScanPoint(0.92f, 0.08f), ScanPoint(0.92f, 0.92f), ScanPoint(0.08f, 0.92f))

/** Longest side of the review bitmap; the corners are fractions, so the preview can be smaller than the photo. */
private const val PREVIEW_SIDE = 1280
private const val LOUPE_ZOOM = 2.5f

/**
 * Review after a manual shutter: the photo with its page outline, four corner handles to drag, and
 * Retake / Use whole photo / Keep. [onKeep] gets the corners as fractions of the upright photo, the
 * same space [com.xfiles.core.documents.ScanProcessing.finishPage] reads.
 */
@Composable
internal fun ScanReview(
    photo: File,
    initial: PageQuad?,
    filter: ScanFilter,
    onFilter: (ScanFilter) -> Unit,
    processing: ScanProcessing,
    onRetake: () -> Unit,
    onKeep: (PageQuad) -> Unit,
) {
    val loaded by produceState<Result<Bitmap>?>(null, photo) {
        val result = withContext(Dispatchers.IO) { runCatching { decodeUprightPreview(photo) } }
        // A decode finished after leaving the screen must not leak its bitmap.
        if (isActive) value = result else result.getOrNull()?.recycle()
    }
    DisposableEffect(loaded) { onDispose { loaded?.getOrNull()?.recycle() } }

    var quad by remember(photo) { mutableStateOf(initial ?: InsetPage) }
    var grabbed by remember(photo) { mutableStateOf(-1) }
    var boxPx by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current
    val reach = with(LocalDensity.current) { 48.dp.toPx() }
    val picture = loaded?.getOrNull()
    val image = remember(picture) { picture?.asImageBitmap() }

    // Filtered previews, one per filter, built off the main thread and kept until this review closes.
    val previews = remember(photo) { HashMap<ScanFilter, Bitmap>() }
    DisposableEffect(photo) { onDispose { previews.values.forEach(Bitmap::recycle); previews.clear() } }
    // Keyed on the filter: choosing another cancels the build still running for the previous one.
    val built by produceState<Pair<ScanFilter, ImageBitmap>?>(null, filter, picture) {
        val source = picture ?: return@produceState
        if (filter == ScanFilter.Original) return@produceState
        val cached = previews[filter]
        val bitmap = cached ?: withContext(Dispatchers.Default) { processing.previewCopy(source, filter) }
        if (cached == null) {
            // A build that finished after leaving the screen must not leak its bitmap.
            if (isActive) previews[filter] = bitmap else return@produceState bitmap.recycle()
        }
        value = filter to bitmap.asImageBitmap()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallows taps so the camera controls underneath do not fire.
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Adjust the corners",
            color = Color.White,
            modifier = Modifier.padding(16.dp),
        )
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (picture == null || image == null) {
                Text(
                    if (loaded?.isFailure == true) "This photo could not be opened." else "Loading photo…",
                    color = Color.White,
                )
            } else {
                val ratio = picture.width.toFloat() / picture.height
                val fitWidth = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
                val fitHeight = fitWidth / ratio
                Box(Modifier.size(fitWidth, fitHeight)) {
                    // Shows the filtered preview once built; until then, and for Original, the photo itself.
                    val shown = built?.takeIf { it.first == filter }?.second ?: image
                    Crossfade(targetState = shown, modifier = Modifier.fillMaxSize(), label = "scan-preview") { current ->
                        Image(
                            bitmap = current,
                            contentDescription = "Photo to adjust",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { boxPx = it }
                            .pointerInput(photo) {
                                var corner = -1
                                var anchor = Offset.Zero
                                var travel = Offset.Zero
                                var width = 1f
                                var height = 1f
                                detectDragGestures(
                                    onDragStart = { start ->
                                        width = boxPx.width.coerceAtLeast(1).toFloat()
                                        height = boxPx.height.coerceAtLeast(1).toFloat()
                                        corner = nearestCorner(quad, start, width, height, reach)
                                        if (corner >= 0) {
                                            grabbed = corner
                                            travel = Offset.Zero
                                            anchor = quad.corners[corner].let { Offset(it.x, it.y) }
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    },
                                    onDrag = { change, drag ->
                                        if (corner >= 0) {
                                            change.consume()
                                            travel += drag
                                            quad = quad.moveCorner(
                                                corner,
                                                ScanPoint(anchor.x + travel.x / width, anchor.y + travel.y / height),
                                            )
                                        }
                                    },
                                    onDragEnd = {
                                        if (corner >= 0) {
                                            corner = -1
                                            grabbed = -1
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    onDragCancel = {
                                        if (corner >= 0) {
                                            corner = -1
                                            grabbed = -1
                                        }
                                    },
                                )
                            },
                    ) {
                        val points = quad.corners.map { Offset(it.x * size.width, it.y * size.height) }
                        val path = Path().apply {
                            points.forEachIndexed { i, at -> if (i == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y) }
                            close()
                        }
                        drawPath(path, EzColors.Positive.copy(alpha = 0.18f))
                        drawPath(path, EzColors.Positive, style = Stroke(width = 2.dp.toPx()))
                        val handle = 10.dp.toPx()
                        points.forEachIndexed { i, at ->
                            val pressed = i == grabbed
                            drawCircle(Color.White, radius = if (pressed) handle * 1.3f else handle, center = at)
                            drawCircle(EzColors.Positive, radius = handle, center = at, style = Stroke(width = 3.dp.toPx()))
                        }
                        val held = grabbed
                        if (held >= 0) drawLoupe(picture, image, quad.corners[held], size.width, size.height)
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFilter.entries.forEach { option ->
                    FilterChip(selected = filter == option, onClick = { onFilter(option) }, label = { Text(option.label, color = Color.White) })
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRetake) { Text("Retake", color = Color.White) }
                Button(
                    onClick = { onKeep(quad) },
                    enabled = picture != null,
                    colors = ButtonDefaults.buttonColors(containerColor = EzColors.Primary, contentColor = EzColors.OnPrimary),
                ) { Text("Keep") }
            }
            TextButton(onClick = { quad = PageQuad.FullFrame }, enabled = picture != null) {
                Text("Use whole photo", color = Color.White)
            }
        }
    }
}

/** Magnifier shown above the grabbed corner, so the finger does not hide the spot being placed. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLoupe(
    picture: Bitmap,
    image: androidx.compose.ui.graphics.ImageBitmap,
    corner: ScanPoint,
    width: Float,
    height: Float,
) {
    val radius = 56.dp.toPx()
    val gap = 28.dp.toPx()
    val at = Offset(corner.x * width, corner.y * height)
    val above = at.y - 2 * radius - gap >= 0f
    val centre = Offset(
        at.x.coerceIn(radius, (width - radius).coerceAtLeast(radius)),
        if (above) at.y - radius - gap else at.y + radius + gap,
    )
    val scale = width / picture.width
    val side = ((2 * radius) / (LOUPE_ZOOM * scale)).roundToInt().coerceIn(2, minOf(picture.width, picture.height))
    val left = (corner.x * picture.width - side / 2f).roundToInt().coerceIn(0, picture.width - side)
    val top = (corner.y * picture.height - side / 2f).roundToInt().coerceIn(0, picture.height - side)
    val diameter = (2 * radius).roundToInt()
    clipPath(Path().apply { addOval(Rect(centre, radius)) }) {
        drawRect(Color.Black, topLeft = centre - Offset(radius, radius), size = androidx.compose.ui.geometry.Size(diameter.toFloat(), diameter.toFloat()))
        drawImage(
            image,
            srcOffset = IntOffset(left, top),
            srcSize = IntSize(side, side),
            dstOffset = IntOffset((centre.x - radius).roundToInt(), (centre.y - radius).roundToInt()),
            dstSize = IntSize(diameter, diameter),
        )
    }
    drawCircle(Color.White, radius = radius, center = centre, style = Stroke(width = 2.dp.toPx()))
    drawCircle(EzColors.Positive, radius = 3.dp.toPx(), center = centre)
}

/** The corner within [reach] pixels of [at], or -1. */
private fun nearestCorner(quad: PageQuad, at: Offset, width: Float, height: Float, reach: Float): Int {
    var best = -1
    var bestDistance = reach
    quad.corners.forEachIndexed { i, p ->
        val distance = (Offset(p.x * width, p.y * height) - at).getDistance()
        if (distance <= bestDistance) {
            best = i
            bestDistance = distance
        }
    }
    return best
}

/**
 * The photo at review size, turned upright from its EXIF rotation exactly as
 * [com.xfiles.core.documents.ScanProcessing] does, so the corners line up with what gets straightened.
 */
private fun decodeUprightPreview(photo: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(photo.path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_SIDE) sample *= 2
    val decoded = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("The photo could not be read.")
    val degrees = when (ExifInterface(photo.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
        .also { decoded.recycle() }
}
