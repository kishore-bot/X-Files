package com.xfiles.scan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** A point as fractions of the picture's size (0..1). */
@Serializable
data class ScanPoint(val x: Float, val y: Float)

/**
 * A page outline. Each corner is where that corner of the finished page comes
 * from, so the four run clockwise; [rotatedClockwise] turns the page by shifting them.
 */
@Serializable
data class PageQuad(
    val topLeft: ScanPoint,
    val topRight: ScanPoint,
    val bottomRight: ScanPoint,
    val bottomLeft: ScanPoint,
) {
    val corners: List<ScanPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun rotatedClockwise(): PageQuad = PageQuad(bottomLeft, topLeft, topRight, bottomRight)

    /** Signed share of the picture; positive when the corners run clockwise on screen. */
    fun area(): Float {
        val c = corners
        var sum = 0f
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2
    }

    fun isConvex(): Boolean {
        val c = corners
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val d = c[(i + 2) % 4]
            if ((b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x) <= 0f) return false
        }
        return true
    }

    /**
     * [index] dragged to [to], kept inside the picture. Returns this quad
     * unchanged when the move would fold the outline or shrink it to nothing.
     */
    fun moveCorner(index: Int, to: ScanPoint): PageQuad {
        val clamped = ScanPoint(to.x.coerceIn(0f, 1f), to.y.coerceIn(0f, 1f))
        val moved = when (index) {
            0 -> copy(topLeft = clamped)
            1 -> copy(topRight = clamped)
            2 -> copy(bottomRight = clamped)
            3 -> copy(bottomLeft = clamped)
            else -> return this
        }
        return if (moved.isConvex() && moved.area() >= MIN_MANUAL_AREA) moved else this
    }

    companion object {
        val FullFrame = PageQuad(ScanPoint(0f, 0f), ScanPoint(1f, 0f), ScanPoint(1f, 1f), ScanPoint(0f, 1f))
    }
}

/** Below this share of the picture, a dragged outline is taken as a slip. */
private const val MIN_MANUAL_AREA = 0.01f

/**
 * Exactly four points as top-left, top-right, bottom-right, bottom-left:
 * clockwise around their centre, starting from the point nearest the picture's
 * top-left. Points that do not form a convex shape still come back in that order.
 */
fun orderCorners(points: List<ScanPoint>): PageQuad {
    require(points.size == 4) { "A page has four corners." }
    val cx = points.sumOf { it.x.toDouble() } / 4
    val cy = points.sumOf { it.y.toDouble() } / 4
    val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
    val start = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
    val p = List(4) { clockwise[(start + it) % 4] }
    return PageQuad(p[0], p[1], p[2], p[3])
}

/**
 * Pixel size of the straightened page for a [quad] on a picture of
 * [imageWidth] x [imageHeight]: the longer of each pair of opposite edges, so
 * no detail is thrown away, scaled down to fit [maxSide]. With a strong
 * perspective the proportions are approximate; finding the true ones would
 * need the camera's focal length.
 */
fun pageOutputSize(quad: PageQuad, imageWidth: Int, imageHeight: Int, maxSide: Int): Pair<Int, Int> {
    fun length(a: ScanPoint, b: ScanPoint) = hypot((a.x - b.x) * imageWidth, (a.y - b.y) * imageHeight)
    val width = max(length(quad.topLeft, quad.topRight), length(quad.bottomLeft, quad.bottomRight))
    val height = max(length(quad.topLeft, quad.bottomLeft), length(quad.topRight, quad.bottomRight))
    val scale = min(1f, maxSide / max(max(width, height), 1f))
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/** Steps of direction for the line search: 2 degrees. */
private const val ANGLE_BINS = 90
private const val BIN_DEGREES = 180f / ANGLE_BINS
private const val BIN_RADIANS = (PI / ANGLE_BINS).toFloat()
private const val RHO_STEP = 2f

/** A step in brightness of about 16 levels out of 255; weaker edges are ignored. */
private const val MIN_EDGE_STRENGTH = 40

/** Edges this far off a pixel's own gradient direction get no vote. */
private const val VOTE_SPREAD_BINS = 4
private const val MAX_CANDIDATE_LINES = 14

/** A side's direction must match the edge pixels under it to within this many bins (20 degrees). */
private const val SUPPORT_ANGLE_BINS = 10

/** Opposite sides can converge this much under perspective. */
private const val MAX_PARALLEL_DEGREES = 30f
private const val MIN_CROSS_DEGREES = 45f
private const val MIN_PAGE_AREA = 0.12f

/** Share of each side, and of the whole outline, that must lie on a matching edge. */
private const val MIN_SIDE_SUPPORT = 0.4f
private const val MIN_MEAN_SUPPORT = 0.6f

/** A corner may fall this far outside the picture (a page slightly out of frame) and is then clamped. */
private const val CORNER_OVERFLOW = 0.06f

/** More than this is a sign the caller forgot to downsample. */
private const val MAX_INPUT_PIXELS = 1_000_000

/**
 * Finds a page's four corners in a downsampled picture.
 *
 * [luma] holds one unsigned brightness byte per pixel, row by row. Edges are
 * extracted with Sobel and thinned, straight lines are found by a Hough
 * transform steered by each edge's own direction, and every plausible
 * combination of four lines is scored by how much of its outline really lies
 * on edges, times the square root of its area. Returns `null` when nothing
 * convincing is found; the caller then asks the user to place the corners.
 *
 * This is a heuristic, checked only on synthetic pictures. It needs some
 * contrast between page and background, so white paper on a white desk fails;
 * curled pages are fitted with straight sides; and a strongly lined background
 * (a tiled floor, a keyboard) or a page with a heavy border can win over the
 * real outline. The user can always correct the result by hand.
 */
fun detectPageOutline(luma: ByteArray, width: Int, height: Int): PageQuad? {
    require(width > 0 && height > 0 && luma.size >= width.toLong() * height) { "The array is smaller than the picture." }
    if (min(width, height) < 16 || width.toLong() * height > MAX_INPUT_PIXELS) return null
    val edges = EdgeMap.of(luma, width, height) ?: return null
    val lines = findLines(edges)
    if (lines.size < 4) return null
    return bestOutline(lines, edges)
}

/** Direction of each thinned edge pixel's normal, in angle bins, or -1 for no edge. */
private class EdgeMap(val width: Int, val height: Int, val angle: ByteArray) {
    fun hasEdgeNear(x: Int, y: Int, bin: Int): Boolean {
        for (dy in -1..1) {
            val row = y + dy
            if (row !in 0 until height) continue
            for (dx in -1..1) {
                val column = x + dx
                if (column !in 0 until width) continue
                val found = angle[row * width + column].toInt()
                if (found >= 0 && binDistance(found, bin) <= SUPPORT_ANGLE_BINS) return true
            }
        }
        return false
    }

    companion object {
        fun of(luma: ByteArray, width: Int, height: Int): EdgeMap? {
            val smooth = blur(blur(IntArray(width * height) { luma[it].toInt() and 0xFF }, width, height), width, height)
            val gx = IntArray(width * height)
            val gy = IntArray(width * height)
            val strength = IntArray(width * height)
            var strongest = 0
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val i = y * width + x
                    val a = smooth[i - width - 1]
                    val b = smooth[i - width]
                    val c = smooth[i - width + 1]
                    val d = smooth[i - 1]
                    val f = smooth[i + 1]
                    val g = smooth[i + width - 1]
                    val h = smooth[i + width]
                    val k = smooth[i + width + 1]
                    gx[i] = c + 2 * f + k - a - 2 * d - g
                    gy[i] = g + 2 * h + k - a - 2 * b - c
                    strength[i] = abs(gx[i]) + abs(gy[i])
                    strongest = max(strongest, strength[i])
                }
            }
            // Relative to the picture's contrast, so faint shading does not vote beside a sharp page edge.
            val threshold = max(MIN_EDGE_STRENGTH, strongest / 8)
            if (strongest < threshold) return null
            val angle = ByteArray(width * height) { -1 }
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val i = y * width + x
                    val s = strength[i]
                    if (s < threshold) continue
                    val absX = abs(gx[i])
                    val absY = abs(gy[i])
                    // Thin: keep only the peak across the edge, on the nearest of the four neighbour axes.
                    val step = when {
                        absX > 2 * absY -> 1
                        absY > 2 * absX -> width
                        gx[i] xor gy[i] >= 0 -> width + 1
                        else -> width - 1
                    }
                    if (s < strength[i - step] || s <= strength[i + step]) continue
                    angle[i] = normalAngleBin(gx[i], gy[i]).toByte()
                }
            }
            return EdgeMap(width, height, angle)
        }

        private fun blur(source: IntArray, width: Int, height: Int): IntArray {
            val across = IntArray(source.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val left = source[y * width + max(x - 1, 0)]
                    val right = source[y * width + min(x + 1, width - 1)]
                    across[y * width + x] = left + 2 * source[y * width + x] + right
                }
            }
            val result = IntArray(source.size)
            for (y in 0 until height) {
                val up = max(y - 1, 0) * width
                val down = min(y + 1, height - 1) * width
                for (x in 0 until width) {
                    result[y * width + x] = (across[up + x] + 2 * across[y * width + x] + across[down + x] + 8) / 16
                }
            }
            return result
        }
    }
}

private fun normalAngleBin(gx: Int, gy: Int): Int {
    var degrees = atan2(gy.toFloat(), gx.toFloat()) * 180f / PI.toFloat()
    if (degrees < 0f) degrees += 180f
    return (degrees / BIN_DEGREES).toInt().coerceAtMost(ANGLE_BINS - 1)
}

/** Distance between two line directions, which wrap around at half a turn. */
private fun binDistance(a: Int, b: Int): Int {
    val d = abs(a - b)
    return min(d, ANGLE_BINS - d)
}

/** The line x·cos(theta) + y·sin(theta) = rho, with theta in 0..180 degrees. */
private class Line(val theta: Float, val rho: Float, val votes: Int) {
    val cosT = cos(theta)
    val sinT = sin(theta)
}

private fun findLines(edges: EdgeMap): List<Line> {
    val width = edges.width
    val height = edges.height
    val diagonal = hypot(width.toFloat(), height.toFloat())
    val rhoBins = ceil(2 * diagonal / RHO_STEP).toInt() + 1
    val cosines = FloatArray(ANGLE_BINS) { cos((it + 0.5f) * BIN_RADIANS) }
    val sines = FloatArray(ANGLE_BINS) { sin((it + 0.5f) * BIN_RADIANS) }
    val votes = IntArray(ANGLE_BINS * rhoBins)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val own = edges.angle[y * width + x].toInt()
            if (own < 0) continue
            for (offset in -VOTE_SPREAD_BINS..VOTE_SPREAD_BINS) {
                val bin = (own + offset).mod(ANGLE_BINS)
                val rho = x * cosines[bin] + y * sines[bin]
                val slot = ((rho + diagonal) / RHO_STEP).roundToInt().coerceIn(0, rhoBins - 1)
                votes[bin * rhoBins + slot]++
            }
        }
    }
    // A side must cover at least a fifth of the picture's shorter side.
    val minVotes = min(width, height) / 5
    val lines = ArrayList<Line>(MAX_CANDIDATE_LINES)
    while (lines.size < MAX_CANDIDATE_LINES) {
        var best = -1
        for (i in votes.indices) if (votes[i] > (if (best < 0) 0 else votes[best])) best = i
        if (best < 0 || votes[best] < minVotes) break
        val bin = best / rhoBins
        val slot = best % rhoBins
        val found = refine(edges, (bin + 0.5f) * BIN_RADIANS, slot * RHO_STEP - diagonal, votes[best])
        lines += found
        suppress(votes, rhoBins, bin, slot)
    }
    return lines
}

/** Clears a neighbourhood around a peak, so one real edge yields one line. */
private fun suppress(votes: IntArray, rhoBins: Int, bin: Int, slot: Int) {
    for (offset in -6..6) {
        val raw = bin + offset
        val wrapped = raw.mod(ANGLE_BINS)
        // Past the wrap at 180 degrees the same line has the opposite rho.
        val mirrored = raw != wrapped
        val middle = if (mirrored) rhoBins - 1 - slot else slot
        for (s in (middle - 6)..(middle + 6)) {
            if (s in 0 until rhoBins) votes[wrapped * rhoBins + s] = 0
        }
    }
}

/** Least-squares fit through the edge pixels on a coarse line: the transform's bins are 2 degrees wide. */
private fun refine(edges: EdgeMap, theta: Float, rho: Float, votes: Int): Line {
    val cosT = cos(theta)
    val sinT = sin(theta)
    val bin = (theta / BIN_RADIANS).toInt().mod(ANGLE_BINS)
    var count = 0
    var sumX = 0.0
    var sumY = 0.0
    var xx = 0.0
    var xy = 0.0
    var yy = 0.0
    for (y in 0 until edges.height) {
        for (x in 0 until edges.width) {
            val own = edges.angle[y * edges.width + x].toInt()
            if (own < 0 || binDistance(own, bin) > VOTE_SPREAD_BINS + 2) continue
            if (abs(x * cosT + y * sinT - rho) > 3f) continue
            count++
            sumX += x
            sumY += y
            xx += (x * x).toDouble()
            xy += (x * y).toDouble()
            yy += (y * y).toDouble()
        }
    }
    if (count < 8) return Line(theta, rho, votes)
    val meanX = sumX / count
    val meanY = sumY / count
    val a = xx / count - meanX * meanX
    val b = xy / count - meanX * meanY
    val c = yy / count - meanY * meanY
    // The smaller eigenvector of the scatter is the line's normal.
    val smaller = (a + c) / 2 - sqrt(((a - c) / 2) * ((a - c) / 2) + b * b)
    var nx = b
    var ny = smaller - a
    if (abs(nx) + abs(ny) < 1e-6) {
        nx = smaller - c
        ny = b
    }
    if (abs(nx) + abs(ny) < 1e-6) return Line(theta, rho, votes)
    var fitted = atan2(ny, nx).toFloat()
    if (fitted < 0f) fitted += PI.toFloat()
    if (fitted >= PI.toFloat()) fitted -= PI.toFloat()
    return Line(fitted, (meanX * cos(fitted) + meanY * sin(fitted)).toFloat(), votes)
}

private fun angleBetweenDegrees(a: Line, b: Line): Float {
    val d = abs(a.theta - b.theta) * 180f / PI.toFloat()
    return min(d, 180f - d)
}

private fun bestOutline(lines: List<Line>, edges: EdgeMap): PageQuad? {
    val width = edges.width
    val height = edges.height
    val minGap = min(width, height) * 0.1f
    val cx = width / 2f
    val cy = height / 2f

    /** Signed distance of the picture's centre from the line, comparable across a near-parallel pair. */
    fun separated(a: Line, b: Line): Boolean {
        val offsetA = cx * a.cosT + cy * a.sinT - a.rho
        var offsetB = cx * b.cosT + cy * b.sinT - b.rho
        if (a.cosT * b.cosT + a.sinT * b.sinT < 0f) offsetB = -offsetB
        return abs(offsetA - offsetB) >= minGap
    }

    val pairs = ArrayList<Pair<Line, Line>>()
    for (i in lines.indices) {
        for (j in i + 1 until lines.size) {
            if (angleBetweenDegrees(lines[i], lines[j]) <= MAX_PARALLEL_DEGREES && separated(lines[i], lines[j])) {
                pairs += lines[i] to lines[j]
            }
        }
    }
    var best: PageQuad? = null
    var bestScore = 0f
    for (i in pairs.indices) {
        for (j in i + 1 until pairs.size) {
            val (a1, a2) = pairs[i]
            val (b1, b2) = pairs[j]
            if (a1 === b1 || a1 === b2 || a2 === b1 || a2 === b2) continue
            if (minOf(angleBetweenDegrees(a1, b1), angleBetweenDegrees(a1, b2), angleBetweenDegrees(a2, b1), angleBetweenDegrees(a2, b2)) < MIN_CROSS_DEGREES) continue
            val corners = listOf(
                intersection(a1, b1) ?: continue,
                intersection(a1, b2) ?: continue,
                intersection(a2, b2) ?: continue,
                intersection(a2, b1) ?: continue,
            )
            val score = scoreOutline(corners, edges) ?: continue
            if (score > bestScore) {
                bestScore = score
                // Half a pixel, because an edge between columns k-1 and k peaks at index k - 0.5.
                best = orderCorners(
                    corners.map {
                        ScanPoint(((it.x + 0.5f) / width).coerceIn(0f, 1f), ((it.y + 0.5f) / height).coerceIn(0f, 1f))
                    },
                )
            }
        }
    }
    return best
}

/** Corners here are in pixels, unlike [ScanPoint]'s usual fractions. */
private fun intersection(a: Line, b: Line): ScanPoint? {
    val det = a.cosT * b.sinT - a.sinT * b.cosT
    if (abs(det) < 1e-4f) return null
    return ScanPoint((a.rho * b.sinT - b.rho * a.sinT) / det, (a.cosT * b.rho - b.cosT * a.rho) / det)
}

private fun scoreOutline(corners: List<ScanPoint>, edges: EdgeMap): Float? {
    val width = edges.width
    val height = edges.height
    val marginX = width * CORNER_OVERFLOW
    val marginY = height * CORNER_OVERFLOW
    if (corners.any { it.x < -marginX || it.x > width + marginX || it.y < -marginY || it.y > height + marginY }) return null
    val quad = orderCorners(corners)
    if (!quad.isConvex()) return null
    val areaShare = quad.area() / (width.toFloat() * height)
    if (areaShare < MIN_PAGE_AREA) return null
    var total = 0f
    val ordered = quad.corners
    for (i in ordered.indices) {
        val support = sideSupport(ordered[i], ordered[(i + 1) % 4], edges)
        if (support < MIN_SIDE_SUPPORT) return null
        total += support
    }
    val mean = total / 4
    if (mean < MIN_MEAN_SUPPORT) return null
    return mean * sqrt(areaShare)
}

/** Share of a side, as far as it is inside the picture, that lies on an edge running the same way. */
private fun sideSupport(from: ScanPoint, to: ScanPoint, edges: EdgeMap): Float {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = hypot(dx, dy)
    if (length < 1f) return 0f
    var normal = atan2(dx, -dy) * 180f / PI.toFloat()
    if (normal < 0f) normal += 180f
    val bin = (normal / BIN_DEGREES).toInt().coerceAtMost(ANGLE_BINS - 1)
    val samples = (length / 2f).toInt().coerceAtLeast(4)
    var inside = 0
    var hits = 0
    for (s in 0..samples) {
        val t = s.toFloat() / samples
        val x = (from.x + dx * t).roundToInt()
        val y = (from.y + dy * t).roundToInt()
        if (x !in 0 until edges.width || y !in 0 until edges.height) continue
        inside++
        if (edges.hasEdgeNear(x, y, bin)) hits++
    }
    return if (inside == 0) 0f else hits.toFloat() / inside
}
