package com.xfiles.documents.book

/** Where one page ends: [next] is the first block of the following page. [oversized]: block `start` alone is taller than the page and must scroll. */
data class PageCut(val next: Int, val oversized: Boolean)

/**
 * Fills one page from block [start]: blocks of height [heightOf] px, separated by [gap] px, go on
 * while the next one still fits in [pageHeight] px. The first block always goes on the page, so the
 * cut always advances. Sorted [chapterStarts] stop the page before the next chapter.
 * [heightOf] is called only for blocks the page takes or tests.
 */
fun cutPage(
    start: Int,
    blockCount: Int,
    pageHeight: Int,
    gap: Int,
    chapterStarts: List<Int> = emptyList(),
    heightOf: (Int) -> Int,
): PageCut {
    if (start >= blockCount) return PageCut(blockCount, false)
    val match = chapterStarts.binarySearch(start)
    val nextChapter = if (match >= 0) match + 1 else -match - 1
    val end = chapterStarts.getOrNull(nextChapter)?.coerceAtMost(blockCount) ?: blockCount
    var used = 0
    var index = start
    while (index < end) {
        val height = heightOf(index).coerceAtLeast(0)
        val needed = if (index == start) height else gap + height
        if (index > start && used + needed > pageHeight) break
        used += needed
        index++
        if (used >= pageHeight) break
    }
    val oversized = index == start + 1 && heightOf(start) > pageHeight
    return PageCut(index, oversized)
}

/**
 * The first block of each page, grown only as far as the reader needs (the page on screen, or the
 * block being jumped to), so a long book is never laid out at once. Lookups are binary searches.
 */
class PageIndex(val blockCount: Int) {
    private var starts = IntArray(INITIAL_CAPACITY)
    private var oversized = BooleanArray(INITIAL_CAPACITY)
    private var nextStart = 0

    /** Pages cut so far. */
    var pageCount: Int = 0
        private set

    /** True once the last page reaches the end of the book (immediately, for an empty book). */
    var isComplete: Boolean = blockCount <= 0
        private set

    /** Whether [block] lies on a page already cut. */
    fun covers(block: Int): Boolean = isComplete || block < nextStart

    fun pageStart(page: Int): Int = starts[page]

    /** First block after [page]: the next page's start, or the end of the cut pages for the last one. */
    fun pageEnd(page: Int): Int = if (page + 1 < pageCount) starts[page + 1] else nextStart

    fun isOversized(page: Int): Boolean = oversized[page]

    /** The page holding [block]: the last cut page if [block] is beyond them. Returns 0 for an empty index. */
    fun pageOf(block: Int): Int {
        var lo = 0
        var hi = pageCount - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= block) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** Cuts the next page with [cut] (given its start block). Returns false if the book is already fully paged. */
    fun extendOnePage(cut: (start: Int) -> PageCut): Boolean {
        if (isComplete) return false
        val start = nextStart
        val result = cut(start)
        // Guard: a cut must advance, or the loop that calls this would never end.
        val next = maxOf(result.next, start + 1)
        if (pageCount == starts.size) {
            starts = starts.copyOf(pageCount * 2)
            oversized = oversized.copyOf(pageCount * 2)
        }
        starts[pageCount] = start
        oversized[pageCount] = result.oversized
        pageCount++
        nextStart = next
        if (next >= blockCount) isComplete = true
        return true
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
    }
}
