package com.xfiles.documents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PdfPageSelectionTest {
    @Test
    fun retainsChosenOrderAndDeduplicatesOverlappingRanges() {
        assertEquals(listOf(4, 0, 1, 2), parsePdfPageSelection("5, 1-3, 2", 6))
    }

    @Test
    fun rejectsInvalidSelectionsBeforeAllocating() {
        for (selection in listOf("", "0", "1,", "4-2", "1-7", "2--3", "x")) {
            assertNull(parsePdfPageSelection(selection, 6), selection)
        }
        assertNull(parsePdfPageSelection("1-501", 10_000))
    }
}
