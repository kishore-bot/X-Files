package com.xfiles.documents.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookPaginationTest {
    private val heights = intArrayOf(40, 40, 40, 100, 10)

    @Test
    fun aPageTakesBlocksWhileTheyFitWithTheGaps() {
        // 40 + 5 + 40 = 85 fits in 90; adding 40 more (+5) would need 130.
        val cut = cutPage(0, heights.size, pageHeight = 90, gap = 5) { heights[it] }
        assertEquals(PageCut(next = 2, oversized = false), cut)
    }

    @Test
    fun theFirstBlockAlwaysGoesOnAPageEvenIfItIsTaller() {
        val cut = cutPage(3, heights.size, pageHeight = 50, gap = 5) { heights[it] }
        assertEquals(PageCut(next = 4, oversized = true), cut)
    }

    @Test
    fun aCutAlwaysAdvancesAndStopsAtTheEnd() {
        val cut = cutPage(4, heights.size, pageHeight = 1000, gap = 5) { heights[it] }
        assertEquals(PageCut(next = 5, oversized = false), cut)
        assertEquals(PageCut(next = 5, oversized = false), cutPage(5, heights.size, 100, 5) { 1 })
    }

    @Test
    fun pageIndexGrowsOnlyWhenAskedAndLooksUpBlocksByBinarySearch() {
        val index = PageIndex(blockCount = heights.size)
        assertEquals(0, index.pageCount)
        assertFalse(index.covers(0))

        index.extendOnePage { start -> cutPage(start, heights.size, 90, 5) { heights[it] } }
        assertEquals(1, index.pageCount)
        assertTrue(index.covers(1))
        assertFalse(index.covers(2))
        assertFalse(index.isComplete)

        while (index.extendOnePage { start -> cutPage(start, heights.size, 90, 5) { heights[it] } }) Unit
        assertTrue(index.isComplete)
        assertEquals(listOf(0, 2, 3, 4), (0 until index.pageCount).map { index.pageStart(it) })
        assertEquals(0, index.pageOf(0))
        assertEquals(1, index.pageOf(2))
        assertEquals(2, index.pageOf(3))
        assertEquals(3, index.pageOf(4))
        assertEquals(3, index.pageOf(40))
        assertTrue(index.isOversized(2))
    }

    @Test
    fun emptyBookHasNoPagesAndIsComplete() {
        val index = PageIndex(blockCount = 0)
        assertTrue(index.isComplete)
        assertFalse(index.extendOnePage { PageCut(0, false) })
        assertEquals(0, index.pageCount)
    }

    @Test
    fun aMisbehavingCutCannotLoopForever() {
        val index = PageIndex(blockCount = 3)
        var calls = 0
        while (index.extendOnePage { PageCut(next = 0, oversized = false).also { calls++ } } && calls < 10) Unit
        assertTrue(index.isComplete)
        assertEquals(3, calls)
    }
}
