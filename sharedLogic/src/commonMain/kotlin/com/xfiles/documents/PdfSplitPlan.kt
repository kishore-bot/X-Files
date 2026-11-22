package com.xfiles.documents

import kotlin.math.min

/**
 * How a PDF is cut. Every plan reduces to the same [PdfPageSpan] list the engine
 * already accepts, so the modes add no engine work. Text fields stay raw strings
 * so the screen can show the user's typing and its error as they type.
 */
sealed interface PdfSplitPlan {
    /** Ranges such as `1-3, 4-8, 9-`, checked by [parsePdfSpans]. */
    data class Ranges(val text: String) : PdfSplitPlan

    /** Consecutive parts of [pagesText] pages each; the last part takes whatever is left. */
    data class EveryPages(val pagesText: String) : PdfSplitPlan

    /** One part per page. */
    data object OnePerPage : PdfSplitPlan
}

/** Spans for [plan] over a PDF of [pageCount] pages, or why the plan can't be applied. */
fun planPdfSplit(plan: PdfSplitPlan, pageCount: Int): PdfSpanParse = when (plan) {
    is PdfSplitPlan.Ranges -> parsePdfSpans(plan.text, pageCount)
    is PdfSplitPlan.EveryPages -> everyPagesSpans(plan.pagesText, pageCount)
    PdfSplitPlan.OnePerPage -> onePerPageSpans(pageCount)
}

internal fun everyPagesSpans(text: String, pageCount: Int): PdfSpanParse {
    if (pageCount < 1) return PdfSpanParse.Invalid("This PDF has no pages.", -1)
    val perPart = text.trim().toIntOrNull() ?: return PdfSpanParse.Invalid("Enter how many pages each file gets.", -1)
    if (perPart < 1) return PdfSpanParse.Invalid("Each file needs at least 1 page.", -1)
    val parts = (pageCount + perPart - 1) / perPart
    if (parts > MAX_PDF_SPLIT_PARTS) {
        return PdfSpanParse.Invalid("That makes $parts files. Split into at most $MAX_PDF_SPLIT_PARTS at a time.", -1)
    }
    val spans = List(parts) { part ->
        val start = part * perPart
        PdfPageSpan(start, min(start + perPart, pageCount) - 1)
    }
    return PdfSpanParse.Valid(spans)
}

internal fun onePerPageSpans(pageCount: Int): PdfSpanParse {
    if (pageCount < 1) return PdfSpanParse.Invalid("This PDF has no pages.", -1)
    if (pageCount > MAX_PDF_SPLIT_PARTS) {
        return PdfSpanParse.Invalid("This PDF has $pageCount pages. Split into at most $MAX_PDF_SPLIT_PARTS files at a time.", -1)
    }
    return PdfSpanParse.Valid(List(pageCount) { PdfPageSpan(it, it) })
}
