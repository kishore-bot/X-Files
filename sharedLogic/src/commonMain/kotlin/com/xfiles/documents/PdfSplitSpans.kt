package com.xfiles.documents

/** One part of a split: zero-based, inclusive page indices. */
data class PdfPageSpan(val start: Int, val end: Int) {
    val size: Int get() = end - start + 1
}

sealed interface PdfSpanParse {
    data class Valid(val spans: List<PdfPageSpan>) : PdfSpanParse

    /** [index] is the part that failed, or -1 when the whole input is empty. */
    data class Invalid(val message: String, val index: Int) : PdfSpanParse
}

/** Most parts one split may produce. Mirrors the engine's bound. */
const val MAX_PDF_SPLIT_PARTS = 256

private val SPAN_PATTERN = Regex("""(\d+)\s*(?:-\s*(\d*))?""")

/**
 * Parses a 1-based list such as `1-3, 4-8, 9-` (an open end runs to the last page).
 * Checks each part's bounds, then looks for shared pages: sorted by start, two
 * parts overlap exactly when one starts at or before the previous one's end.
 * Adjacent parts are fine. The engine checks the same things again before writing.
 */
fun parsePdfSpans(text: String, pageCount: Int): PdfSpanParse {
    val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return PdfSpanParse.Invalid("Add at least one page range.", -1)
    if (parts.size > MAX_PDF_SPLIT_PARTS) {
        return PdfSpanParse.Invalid("Split into at most $MAX_PDF_SPLIT_PARTS parts at a time.", -1)
    }
    val spans = ArrayList<PdfPageSpan>(parts.size)
    for ((index, part) in parts.withIndex()) {
        val match = SPAN_PATTERN.matchEntire(part)
            ?: return PdfSpanParse.Invalid("\"$part\" isn't a page range. Try 1-3, 5 or 9-.", index)
        val first = match.groupValues[1].toIntOrNull() ?: 0
        val hasDash = part.contains('-')
        val last = if (!hasDash) first else match.groupValues[2].toIntOrNull() ?: pageCount
        if (first < 1 || last < 1) return PdfSpanParse.Invalid("Pages start at 1.", index)
        if (first > last) return PdfSpanParse.Invalid("\"$part\" starts after it ends.", index)
        if (last > pageCount) return PdfSpanParse.Invalid("This PDF has $pageCount pages.", index)
        spans += PdfPageSpan(first - 1, last - 1)
    }
    overlappingPair(spans)?.let { (earlier, later) ->
        return PdfSpanParse.Invalid(
            "Parts ${earlier + 1} and ${later + 1} share a page. Each page can go in one part.",
            later,
        )
    }
    return PdfSpanParse.Valid(spans)
}

/**
 * Index pair (in the original order) of the first two spans that share a page,
 * found by sorting by start and comparing neighbours: O(k log k).
 */
internal fun overlappingPair(spans: List<PdfPageSpan>): Pair<Int, Int>? {
    val order = spans.indices.sortedWith(compareBy({ spans[it].start }, { spans[it].end }))
    for (k in 1 until order.size) {
        val previous = spans[order[k - 1]]
        val current = spans[order[k]]
        if (current.start <= previous.end) {
            return minOf(order[k - 1], order[k]) to maxOf(order[k - 1], order[k])
        }
    }
    return null
}

/** The default output name for a part, such as `Report part 2 (pages 4-8).pdf`. */
fun pdfSplitPartName(base: String, index: Int, span: PdfPageSpan): String {
    val stem = base.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Document" }
    return "$stem part ${index + 1} (pages ${span.start + 1}-${span.end + 1}).pdf"
}
