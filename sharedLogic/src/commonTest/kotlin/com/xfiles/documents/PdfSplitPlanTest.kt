package com.xfiles.documents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PdfSplitPlanTest {
    @Test
    fun everyPagesCutsEvenlyAndLeavesTheRemainderInTheLastPart() {
        val parse = planPdfSplit(PdfSplitPlan.EveryPages("3"), 8)
        assertIs<PdfSpanParse.Valid>(parse)
        assertEquals(
            listOf(PdfPageSpan(0, 2), PdfPageSpan(3, 5), PdfPageSpan(6, 7)),
            parse.spans,
        )
    }

    @Test
    fun everyPagesOfOneGivesOnePartPerPage() {
        val parse = planPdfSplit(PdfSplitPlan.EveryPages(" 1 "), 3)
        assertIs<PdfSpanParse.Valid>(parse)
        assertEquals(listOf(PdfPageSpan(0, 0), PdfPageSpan(1, 1), PdfPageSpan(2, 2)), parse.spans)
    }

    @Test
    fun everyPagesLargerThanTheDocumentIsOneWholePart() {
        val parse = planPdfSplit(PdfSplitPlan.EveryPages("10"), 4)
        assertIs<PdfSpanParse.Valid>(parse)
        assertEquals(listOf(PdfPageSpan(0, 3)), parse.spans)
    }

    @Test
    fun everyPagesRejectsBlankZeroAndNonNumericInput() {
        assertIs<PdfSpanParse.Invalid>(planPdfSplit(PdfSplitPlan.EveryPages(""), 4))
        assertIs<PdfSpanParse.Invalid>(planPdfSplit(PdfSplitPlan.EveryPages("0"), 4))
        assertIs<PdfSpanParse.Invalid>(planPdfSplit(PdfSplitPlan.EveryPages("two"), 4))
    }

    @Test
    fun everyPagesRefusesMoreFilesThanTheEngineAccepts() {
        // 600 pages one at a time is 600 parts; the cap is 256.
        assertIs<PdfSpanParse.Invalid>(planPdfSplit(PdfSplitPlan.EveryPages("1"), 600))
        assertIs<PdfSpanParse.Valid>(planPdfSplit(PdfSplitPlan.EveryPages("3"), 600))
    }

    @Test
    fun onePerPageGivesEveryPageItsOwnPart() {
        val parse = planPdfSplit(PdfSplitPlan.OnePerPage, 2)
        assertIs<PdfSpanParse.Valid>(parse)
        assertEquals(listOf(PdfPageSpan(0, 0), PdfPageSpan(1, 1)), parse.spans)
    }

    @Test
    fun onePerPageRefusesEmptyAndOversizedDocuments() {
        assertIs<PdfSpanParse.Invalid>(planPdfSplit(PdfSplitPlan.OnePerPage, 0))
        assertIs<PdfSpanParse.Invalid>(planPdfSplit(PdfSplitPlan.OnePerPage, MAX_PDF_SPLIT_PARTS + 1))
    }

    @Test
    fun rangesDelegateToTheTypedParser() {
        assertEquals(parsePdfSpans("1-2, 3", 5), planPdfSplit(PdfSplitPlan.Ranges("1-2, 3"), 5))
    }
}
