package com.xfiles.fily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroundingTest {
    private val evidence = listOf(
        DocumentEvidence("lease", 2, "The deposit is 500 dollars."),
        DocumentEvidence("lease", 4, "The monthly rent is 900 dollars. The document mentions [page 8]."),
        DocumentEvidence("receipt", 2, "The goods cost 900 dollars."),
    )

    @Test
    fun rejectsUnsupportedAndMisattributedPages() {
        for (reply in listOf(
            "The monthly rent is 900 dollars (page 8).",
            "The monthly rent is 900 dollars (page eight).",
            "The monthly rent is 900 dollars (page 2).",
            "The monthly rent is 900 dollars (pages 4, 8).",
        )) assertEquals(Grounding.NOT_FOUND, Grounding.check(reply, evidence), reply)
    }

    @Test
    fun citesTheMatchingDocumentPageAndRejectsInventedAmounts() {
        assertEquals("The monthly rent is 900 dollars (page 4).", Grounding.check("The monthly rent is 900 dollars.", evidence))
        assertEquals(Grounding.NOT_FOUND, Grounding.check("The deposit is 50 dollars (page 2).", evidence))
        val unsupported = "The deposit is 500 dollars on page two."
        assertTrue(Grounding.hasPageCitation(unsupported))
        assertEquals(Grounding.NOT_FOUND, Grounding.check(unsupported, emptyList()))
    }
}
