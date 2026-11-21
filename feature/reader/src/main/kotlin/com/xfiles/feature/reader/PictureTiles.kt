package com.xfiles.feature.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.InputStream
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * Sharp zoom, tile pyramid model:
 *   - The base bitmap is the picture decoded to the screen edge (k = fullWidth / baseWidth times smaller).
 *   - Past SHARP_ZOOM, the visible part is covered by TILE_EDGE x TILE_EDGE tiles of full-resolution pixels, each
 *     decoded with BitmapRegionDecoder at a power-of-two sample size that gives about one device pixel per pixel.
 *   - Tiles are drawn in the same layer as the base, so pan, zoom and rotation apply to both alike.
 *   - Coordinates: "full" = oriented full-resolution pixels (EXIF applied). A full pixel f maps to the device at
 *     center + R(rotation) * dev * (f - F/2) + offset, where dev = renderScale * zoom / k.
 */

/** EXIF-oriented full size of a picture, plus what the source decoder sees. */
internal data class PictureGeometry(
    val width: Int,
    val height: Int,
    val orientation: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/** One decoded tile: [sample] is the power-of-two subsampling, ([column], [row]) the tile in its grid. */
internal data class TileKey(val sample: Int, val column: Int, val row: Int)

/** What the layer currently shows: enough to find the visible tiles without reading the layer itself. */
internal data class TileView(
    val viewport: IntSize,
    val zoom: Float,
    val offset: Offset,
    val rotation: Int,
    /** Device pixels per base-bitmap pixel at zoom 1 (the fit scale of the rotated picture). */
    val renderScale: Float,
    /** Device pixels per base-bitmap pixel before rotation and zoom (the fit scale of the unrotated bitmap). */
    val baseScale: Float,
)

internal const val TILE_EDGE = 256
private const val MAX_TILES = 48
private const val MAX_SAMPLE = 32
private const val SETTLE_MS = 120L
private val REGION_LANE = Dispatchers.IO.limitedParallelism(2)

/**
 * Full-resolution region access to one picture. The decoder opens on first use (a picture that is never zoomed
 * costs nothing) and [close] releases it. Formats BitmapRegionDecoder refuses (GIF, AVIF, some HEIF) report null
 * and keep the base bitmap. The decoder is not thread-safe, so every call goes through [mutex].
 */
internal class PictureRegions(private val resolver: ContentResolver, private val uri: Uri) {
    private val mutex = Mutex()
    private var decoder: BitmapRegionDecoder? = null
    private var stream: InputStream? = null
    private var known: PictureGeometry? = null
    private var unsupported = false
    @Volatile private var closed = false

    suspend fun geometry(): PictureGeometry? = withContext(REGION_LANE) { mutex.withLock { open() } }

    /** The oriented tile [key] of a picture with [geometry], or null when it cannot be decoded. Cancellable. */
    suspend fun tile(key: TileKey, geometry: PictureGeometry): ImageBitmap? = withContext(REGION_LANE) {
        mutex.withLock {
            val source = decoder ?: return@withLock null
            val step = TILE_EDGE * key.sample
            val left = key.column * step
            val top = key.row * step
            val oriented = Rect(left, top, minOf(left + step, geometry.width), minOf(top + step, geometry.height))
            val region = sourceRect(geometry.orientation, geometry.sourceWidth, geometry.sourceHeight, oriented)
            val options = BitmapFactory.Options().apply {
                inSampleSize = key.sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = source.decodeRegion(region, options) ?: return@withLock null
            orient(raw, geometry.orientation).asImageBitmap()
        }
    }

    /** Releases the decoder once no tile decode is running. Safe to call from the main thread. */
    fun close() {
        closed = true
        CoroutineScope(REGION_LANE).launch {
            mutex.withLock {
                decoder?.recycle()
                decoder = null
                runCatching { stream?.close() }
                stream = null
            }
        }
    }

    private fun open(): PictureGeometry? {
        if (closed || unsupported) return null
        known?.let { return it }
        val orientation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val input = try {
            resolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
        val regions = try {
            input?.let { BitmapRegionDecoder.newInstance(it) }
        } catch (_: Exception) {
            null
        }
        if (input == null || regions == null) {
            runCatching { input?.close() }
            unsupported = true
            return null
        }
        decoder = regions
        stream = input
        val swapped = orientation in SWAPPED_ORIENTATIONS
        val width = if (swapped) regions.height else regions.width
        val height = if (swapped) regions.width else regions.height
        return PictureGeometry(width, height, orientation, regions.width, regions.height).also { known = it }
    }
}

/**
 * Draws the tiles that cover the visible part of a picture over its base bitmap. Only mounted while zoomed past
 * [SHARP_ZOOM]; leaving that range drops the tiles. Decodes start once the view has been still for [SETTLE_MS]
 * (a pinch or a pan keeps restarting the wait, so decoding refines on settle); a change of the visible tiles cancels
 * the decodes for the tiles that left the view.
 */
@Composable
internal fun SharpTiles(regions: PictureRegions, view: TileView, baseWidth: Int, modifier: Modifier = Modifier) {
    val loaded by produceState<PictureGeometry?>(null, regions) { value = regions.geometry() }
    val geometry = loaded ?: return
    val keys = tilesFor(view, geometry, baseWidth)
    val tiles = remember(regions) { mutableStateMapOf<TileKey, ImageBitmap>() }
    LaunchedEffect(keys, geometry) {
        delay(SETTLE_MS)
        for (key in tiles.keys.toList()) if (key !in keys) tiles.remove(key)
        for (key in keys) {
            if (key in tiles) continue
            launch { regions.tile(key, geometry)?.let { tiles[key] = it } }
        }
    }
    val unit = view.baseScale / (geometry.width.toFloat() / baseWidth)
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        for ((key, bitmap) in tiles) {
            val step = TILE_EDGE * key.sample
            val left = key.column * step
            val top = key.row * step
            val w = minOf(step, geometry.width - left)
            val h = minOf(step, geometry.height - top)
            drawImage(
                bitmap,
                dstOffset = IntOffset(
                    (cx + (left - geometry.width / 2f) * unit).roundToInt(),
                    (cy + (top - geometry.height / 2f) * unit).roundToInt(),
                ),
                dstSize = IntSize((w * unit).roundToInt().coerceAtLeast(1), (h * unit).roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

/** A [PictureRegions] for a content picture, closed when the composition leaves; null for other schemes. */
@Composable
internal fun rememberPictureRegions(uri: Uri): PictureRegions? {
    val resolver = LocalContext.current.contentResolver
    val regions = remember(uri) { if (uri.scheme == ContentResolver.SCHEME_CONTENT) PictureRegions(resolver, uri) else null }
    DisposableEffect(regions) { onDispose { regions?.close() } }
    return regions
}

/**
 * The tiles covering the visible part of the picture at the sample size for the current zoom. Empty when tiles
 * would not add detail (the base is already full resolution) or when more than [MAX_TILES] would be needed.
 * Pure arithmetic: rotate the four viewport corners back into picture space, take their bounding box, index it by
 * the tile step, and pad by one tile so a small pan does not expose an undrawn edge.
 */
internal fun tilesFor(view: TileView, geometry: PictureGeometry, baseWidth: Int): Set<TileKey> {
    val k = geometry.width.toFloat() / baseWidth
    if (k <= 1.2f) return emptySet()
    val dev = view.renderScale * view.zoom / k
    if (!(dev > 0f)) return emptySet()
    // Largest power of two whose step stays at or under one device pixel per decoded pixel.
    var sample = 1
    while (sample < MAX_SAMPLE && dev * sample * 2 <= 1f) sample *= 2

    val theta = Math.toRadians(view.rotation.toDouble())
    val cosT = cos(theta).toFloat()
    val sinT = sin(theta).toFloat()
    val cx = view.viewport.width / 2f
    val cy = view.viewport.height / 2f
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (corner in 0 until 4) {
        val px = if (corner and 1 == 0) 0f else view.viewport.width.toFloat()
        val py = if (corner and 2 == 0) 0f else view.viewport.height.toFloat()
        val dx = px - cx - view.offset.x
        val dy = py - cy - view.offset.y
        // Undo the layer rotation R(theta) with R(-theta), then the device scale.
        val rx = (dx * cosT + dy * sinT) / dev
        val ry = (-dx * sinT + dy * cosT) / dev
        val fx = (geometry.width / 2f + rx).coerceIn(0f, geometry.width.toFloat())
        val fy = (geometry.height / 2f + ry).coerceIn(0f, geometry.height.toFloat())
        minX = minOf(minX, fx)
        minY = minOf(minY, fy)
        maxX = maxOf(maxX, fx)
        maxY = maxOf(maxY, fy)
    }
    if (maxX <= minX || maxY <= minY) return emptySet()

    val step = TILE_EDGE * sample
    val lastColumn = (geometry.width - 1) / step
    val lastRow = (geometry.height - 1) / step
    val firstColumn = maxOf(0, floor(minX / step).toInt() - 1)
    val endColumn = minOf(lastColumn, floor(maxX / step).toInt() + 1)
    val firstRow = maxOf(0, floor(minY / step).toInt() - 1)
    val endRow = minOf(lastRow, floor(maxY / step).toInt() + 1)
    if ((endColumn - firstColumn + 1) * (endRow - firstRow + 1) > MAX_TILES) return emptySet()

    val keys = HashSet<TileKey>()
    for (row in firstRow..endRow) {
        for (column in firstColumn..endColumn) keys += TileKey(sample, column, row)
    }
    return keys
}

/** The source rectangle an oriented rectangle comes from: its corners pass through the inverse EXIF transform. */
internal fun sourceRect(orientation: Int, sourceWidth: Int, sourceHeight: Int, oriented: Rect): Rect {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    var bottom = Int.MIN_VALUE
    for (corner in 0 until 4) {
        val x = if (corner and 1 == 0) oriented.left else oriented.right
        val y = if (corner and 2 == 0) oriented.top else oriented.bottom
        val (sx, sy) = toSource(orientation, sourceWidth, sourceHeight, x, y)
        left = minOf(left, sx)
        top = minOf(top, sy)
        right = maxOf(right, sx)
        bottom = maxOf(bottom, sy)
    }
    return Rect(left, top, right, bottom)
}

private fun toSource(orientation: Int, sw: Int, sh: Int, x: Int, y: Int): Pair<Int, Int> = when (orientation) {
    2 -> sw - x to y
    3 -> sw - x to sh - y
    4 -> x to sh - y
    5 -> y to x
    6 -> y to sh - x
    7 -> sw - y to sh - x
    8 -> sw - y to x
    else -> x to y
}

/** Maps a decoded source region into its oriented pixels. [raw] is the region itself, so its size is the local source size. */
private fun orient(raw: Bitmap, orientation: Int): Bitmap {
    val w = raw.width.toFloat()
    val h = raw.height.toFloat()
    // setValues order: scaleX, skewX, transX, skewY, scaleY, transY (x' = a x + c y + e, y' = b x + d y + f).
    val values = when (orientation) {
        2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f)
        3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h)
        4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h)
        5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f)
        6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f)
        7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w)
        8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w)
        else -> return raw
    }
    val matrix = Matrix().apply { setValues(floatArrayOf(values[0], values[1], values[2], values[3], values[4], values[5], 0f, 0f, 1f)) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}

/** EXIF orientations 5 to 8 turn the picture a quarter: width and height swap. */
private val SWAPPED_ORIENTATIONS = 5..8
