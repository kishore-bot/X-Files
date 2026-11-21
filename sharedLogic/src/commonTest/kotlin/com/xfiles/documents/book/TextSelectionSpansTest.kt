package com.xfiles.documents.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextSelectionSpansTest {
    private val lengths = intArrayOf(10, 0, 8, 5)
    private val lengthOf: (Int) -> Int = { lengths[it] }

    @Test
    fun aSelectionInOneBlockIsOneSpan() {
        assertEquals(
            listOf(BlockSpan(2, 1, 4)),
            selectionSpans(TextPoint(2, 4), TextPoint(2, 1), lengthOf),
        )
    }

    @Test
    fun aSelectionAcrossBlocksCoversTheEndsAndTheWholeBlocksBetween() {
        // Block 1 is empty, so it contributes no span.
        assertEquals(
            listOf(BlockSpan(0, 6, 10), BlockSpan(2, 0, 3)),
            selectionSpans(TextPoint(0, 6), TextPoint(2, 3), lengthOf),
        )
        assertEquals(
            listOf(BlockSpan(0, 6, 10), BlockSpan(2, 0, 8), BlockSpan(3, 0, 2)),
            selectionSpans(TextPoint(3, 2), TextPoint(0, 6), lengthOf),
        )
    }

    @Test
    fun offsetsOutsideTheTextAreClamped() {
        assertEquals(listOf(BlockSpan(3, 0, 5)), selectionSpans(TextPoint(3, -4), TextPoint(3, 99), lengthOf))
        assertEquals(emptyList(), selectionSpans(TextPoint(1, 0), TextPoint(1, 0), lengthOf))
    }

    @Test
    fun spanInBlockGivesTheRangeOfEachBlockOnlyWhenItIsCovered() {
        val a = TextPoint(0, 6)
        val b = TextPoint(2, 3)
        assertEquals(6 until 10, spanInBlock(a, b, block = 0, length = 10))
        assertEquals(null, spanInBlock(a, b, block = 1, length = 0))
        assertEquals(0 until 3, spanInBlock(b, a, block = 2, length = 8))
        assertNull(spanInBlock(a, b, block = 3, length = 5))
    }

    @Test
    fun aSelectionCoversAtMostTheBlockLimit() {
        val spans = selectionSpans(TextPoint(0, 0), TextPoint(10_000, 1), lengthOf = { 4 })
        assertEquals(MAX_SELECTED_BLOCKS, spans.size)
    }

    @Test
    fun wordEdgesSnapToTheWordAroundAnOffset() {
        val text = "one two three"
        assertEquals(4, wordStartAt(text, 6))
        assertEquals(7, wordEndAt(text, 6))
        assertEquals(0, wordStartAt("", 0))
    }
}
