package com.xfiles.core.documents

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One run of a PDF page's text with its boxes. Boxes are page fractions (0..1, top-left origin). A run is a
 * text-layer segment (usually a word or a line) or an OCR line; [boxes] has one entry per character when the
 * platform gives per-character bounds, otherwise one per line, and the characters are spread across them.
 */
internal data class PdfTextRun(val text: String, val boxes: List<PdfMatchBox>)

/**
 * A page's characters in reading order, each with its box in page fractions, built once per page and then
 * read-only (safe to share across threads).
 *
 * Hit-testing is O(log n): characters are clustered into bands (lines) ordered by height, and each band keeps
 * its characters ordered by x, so a point is resolved by two binary searches. Synthetic separators (line
 * breaks and spaces between runs) are in [text] for copying but have no box, so they are never hit.
 */
class PdfPageText internal constructor(
    /** The page's text as selected and copied: characters in reading order, with line breaks and spaces. */
    val text: String,
    private val left: FloatArray,
    private val top: FloatArray,
    private val right: FloatArray,
    private val bottom: FloatArray,
    /** Characters with a box, grouped by band; [bandStart] gives each band's span in [bandChars]. */
    private val bandTop: FloatArray,
    private val bandBottom: FloatArray,
    private val bandStart: IntArray,
    private val bandChars: IntArray,
) {
    /** True when the page has no characters with a box (blank, or an image with no text). */
    val isEmpty: Boolean get() = bandChars.isEmpty()

    /**
     * The index of the character nearest to the page point ([x], [y]), or -1 when the page has no boxes.
     * Finds the band by binary search on its height, then the character by binary search on its x.
     */
    fun indexAt(x: Float, y: Float): Int {
        val bands = bandTop.size
        if (bands == 0) return -1
        // First band whose bottom reaches y; bands are ordered top to bottom.
        var lo = 0
        var hi = bands
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bandBottom[mid] < y) lo = mid + 1 else hi = mid
        }
        var band = when {
            lo == bands -> bands - 1
            lo > 0 && y < bandTop[lo] && y - bandBottom[lo - 1] < bandTop[lo] - y -> lo - 1
            else -> lo
        }
        if (band < 0) band = 0
        val from = bandStart[band]
        val to = bandStart[band + 1]
        if (from >= to) return -1
        // Within the band, characters are ordered by x: first whose centre is at or past x, then its neighbour.
        var a = from
        var b = to
        while (a < b) {
            val mid = (a + b) ushr 1
            if (centreX(bandChars[mid]) < x) a = mid + 1 else b = mid
        }
        val candidates = if (a == to) intArrayOf(to - 1) else if (a == from) intArrayOf(from) else intArrayOf(a - 1, a)
        var best = bandChars[candidates[0]]
        var bestDistance = abs(centreX(best) - x)
        for (slot in candidates) {
            val index = bandChars[slot]
            val distance = abs(centreX(index) - x)
            if (distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Boxes covering the characters in [start] until [end] (exclusive), merged per line, in reading order.
     * Called when a selection changes, not per frame.
     */
    fun boxesBetween(start: Int, end: Int): List<PdfMatchBox> {
        val out = ArrayList<PdfMatchBox>()
        var current: PdfMatchBox? = null
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        for (index in from until to) {
            if (!hasBox(index)) continue
            val box = PdfMatchBox(left[index], top[index], right[index], bottom[index])
            val open = current
            if (open != null && sameLine(open, box)) {
                current = PdfMatchBox(min(open.left, box.left), min(open.top, box.top), max(open.right, box.right), max(open.bottom, box.bottom))
            } else {
                if (open != null) out.add(open)
                current = box
            }
        }
        current?.let(out::add)
        return out
    }

    /** The box of character [index] in page fractions, or null for a separator or an out-of-range index. */
    fun boxOf(index: Int): PdfMatchBox? =
        if (index in text.indices && hasBox(index)) PdfMatchBox(left[index], top[index], right[index], bottom[index]) else null

    private fun hasBox(index: Int): Boolean = right[index] > left[index] && bottom[index] > top[index]

    private fun centreX(index: Int): Float = (left[index] + right[index]) / 2f

    companion object {
        /** Builds the page from its runs, in order. Runs with no text or no boxes are skipped. */
        internal fun build(runs: List<PdfTextRun>): PdfPageText {
            val text = StringBuilder()
            val left = ArrayList<Float>()
            val top = ArrayList<Float>()
            val right = ArrayList<Float>()
            val bottom = ArrayList<Float>()
            fun append(char: Char, box: PdfMatchBox?) {
                text.append(char)
                left.add(box?.left ?: 0f)
                top.add(box?.top ?: 0f)
                right.add(box?.right ?: 0f)
                bottom.add(box?.bottom ?: 0f)
            }
            var previous: PdfMatchBox? = null
            for (run in runs) {
                if (run.text.isEmpty() || run.boxes.isEmpty()) continue
                val first = run.boxes.first()
                val prev = previous
                if (prev != null && text.isNotEmpty() && !text.last().isWhitespace() && !run.text.first().isWhitespace()) {
                    val height = (prev.bottom - prev.top).coerceAtLeast(0.001f)
                    if (!sameLine(prev, first)) {
                        append('\n', null)
                    } else if (first.left - prev.right > height * 0.3f) {
                        append(' ', null)
                    }
                }
                distribute(run, ::append)
                previous = run.boxes.last()
            }
            return fromArrays(text.toString(), left.toFloatArray(), top.toFloatArray(), right.toFloatArray(), bottom.toFloatArray())
        }

        /** Spreads the run's characters across its boxes: one per box when counts match, else by box width. */
        private fun distribute(run: PdfTextRun, emit: (Char, PdfMatchBox?) -> Unit) {
            val chars = run.text
            val boxes = run.boxes
            if (boxes.size == chars.length) {
                chars.forEachIndexed { index, char -> emit(char, boxes[index]) }
                return
            }
            val total = boxes.sumOf { (it.right - it.left).coerceAtLeast(0f).toDouble() }.toFloat()
            var position = 0
            boxes.forEachIndexed { k, box ->
                val remaining = chars.length - position
                if (remaining <= 0) return
                val share = when {
                    k == boxes.lastIndex -> remaining
                    total > 0f -> (chars.length * (box.right - box.left).coerceAtLeast(0f) / total).roundToInt().coerceIn(0, remaining)
                    else -> (chars.length / boxes.size).coerceIn(0, remaining)
                }
                val width = box.right - box.left
                for (j in 0 until share) {
                    val l = box.left + width * j / share
                    val r = box.left + width * (j + 1) / share
                    emit(chars[position + j], PdfMatchBox(l, box.top, r, box.bottom))
                }
                position += share
            }
        }

        /** Two boxes on one line when they share more than half of the shorter one's height. */
        internal fun sameLine(prev: PdfMatchBox, next: PdfMatchBox): Boolean {
            val overlap = min(prev.bottom, next.bottom) - max(prev.top, next.top)
            return overlap > min(prev.bottom - prev.top, next.bottom - next.top) / 2f
        }

        private fun fromArrays(text: String, left: FloatArray, top: FloatArray, right: FloatArray, bottom: FloatArray): PdfPageText {
            // Characters with a box, ordered by band (top to bottom, by centre), then by x within a band.
            val boxed = (text.indices).filter { right[it] > left[it] && bottom[it] > top[it] }
                .sortedBy { (top[it] + bottom[it]) / 2f }
            val bandTops = ArrayList<Float>()
            val bandBottoms = ArrayList<Float>()
            val bands = ArrayList<MutableList<Int>>()
            for (index in boxed) {
                val centre = (top[index] + bottom[index]) / 2f
                val band = bands.lastOrNull()
                if (band != null && centre <= bandBottoms.last()) {
                    band.add(index)
                    bandTops[bandTops.lastIndex] = min(bandTops.last(), top[index])
                    bandBottoms[bandBottoms.lastIndex] = max(bandBottoms.last(), bottom[index])
                } else {
                    bands.add(mutableListOf(index))
                    bandTops.add(top[index])
                    bandBottoms.add(bottom[index])
                }
            }
            val starts = IntArray(bands.size + 1)
            val chars = IntArray(boxed.size)
            var cursor = 0
            bands.forEachIndexed { b, band ->
                starts[b] = cursor
                band.sortBy { (left[it] + right[it]) / 2f }
                band.forEach { chars[cursor++] = it }
            }
            starts[bands.size] = cursor
            return PdfPageText(
                text = text,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                bandTop = bandTops.toFloatArray(),
                bandBottom = bandBottoms.toFloatArray(),
                bandStart = starts,
                bandChars = chars,
            )
        }
    }
}
