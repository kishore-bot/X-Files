package com.xfiles.documents.book

/** A value painted over the half-open text range [start, end). */
data class MarkRange<T>(val start: Int, val end: Int, val value: T)

/**
 * Ranges that may overlap, flattened into sorted, disjoint segments, each listing the values that
 * cover it. Built by one sweep over the sorted boundaries, so highlights that overlap keep every
 * colour; a lookup is a binary search over the segment starts.
 */
class SegmentIndex<T> private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val values: List<List<T>>,
) {
    data class Segment<T>(val start: Int, val end: Int, val values: List<T>)

    val size: Int
        get() = starts.size

    /** All segments in text order. Every segment has at least one value. */
    fun segments(): List<Segment<T>> = List(starts.size) { Segment(starts[it], ends[it], values[it]) }

    /** The values covering [offset], or an empty list. */
    fun at(offset: Int): List<T> {
        val index = segmentAt(offset)
        return if (index >= 0) values[index] else emptyList()
    }

    /** Index of the segment containing [offset], or -1. */
    private fun segmentAt(offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= offset) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (found >= 0 && offset < ends[found]) found else -1
    }

    companion object {
        fun <T> empty(): SegmentIndex<T> = SegmentIndex(IntArray(0), IntArray(0), emptyList())

        /**
         * Flattens [ranges] over a text of [length] characters. Ranges are clamped to the text, and
         * empty ones dropped. Each boundary is visited once, in order, while a set of the ranges
         * currently open is kept.
         */
        fun <T> build(ranges: List<MarkRange<T>>, length: Int): SegmentIndex<T> {
            val limit = length.coerceAtLeast(0)
            val kept = ranges.mapNotNull { range ->
                val start = range.start.coerceIn(0, limit)
                val end = range.end.coerceIn(0, limit)
                if (start < end) MarkRange(start, end, range.value) else null
            }
            if (kept.isEmpty()) return empty()

            val byStart = kept.indices.sortedBy { kept[it].start }
            val byEnd = kept.indices.sortedBy { kept[it].end }
            val bounds = (kept.map { it.start } + kept.map { it.end }).distinct().sorted()

            val open = LinkedHashSet<Int>()
            val outStarts = ArrayList<Int>()
            val outEnds = ArrayList<Int>()
            val outValues = ArrayList<List<T>>()
            var nextStart = 0
            var nextEnd = 0
            for (k in 0 until bounds.size - 1) {
                val point = bounds[k]
                // Opening before closing: a range that ends here was opened at an earlier point.
                while (nextStart < byStart.size && kept[byStart[nextStart]].start <= point) {
                    open.add(byStart[nextStart])
                    nextStart++
                }
                while (nextEnd < byEnd.size && kept[byEnd[nextEnd]].end <= point) {
                    open.remove(byEnd[nextEnd])
                    nextEnd++
                }
                if (open.isNotEmpty()) {
                    outStarts.add(point)
                    outEnds.add(bounds[k + 1])
                    outValues.add(open.map { kept[it].value })
                }
            }
            return SegmentIndex(outStarts.toIntArray(), outEnds.toIntArray(), outValues)
        }
    }
}
