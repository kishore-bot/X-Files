package com.xfiles.documents.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentIndexTest {
    @Test
    fun overlappingRangesSplitIntoSegmentsKeepingEveryValue() {
        val index = SegmentIndex.build(
            listOf(MarkRange(0, 10, "a"), MarkRange(5, 15, "b"), MarkRange(20, 25, "c")),
            length = 30,
        )
        assertEquals(
            listOf(
                SegmentIndex.Segment(0, 5, listOf("a")),
                SegmentIndex.Segment(5, 10, listOf("a", "b")),
                SegmentIndex.Segment(10, 15, listOf("b")),
                SegmentIndex.Segment(20, 25, listOf("c")),
            ),
            index.segments(),
        )
    }

    @Test
    fun lookupsAreHalfOpenAndBinarySearched() {
        val index = SegmentIndex.build(listOf(MarkRange(2, 4, 1), MarkRange(3, 6, 2)), length = 10)
        assertEquals(emptyList(), index.at(1))
        assertEquals(listOf(1), index.at(2))
        assertEquals(listOf(1, 2), index.at(3))
        assertEquals(listOf(2), index.at(4))
        assertEquals(listOf(2), index.at(5))
        assertEquals(emptyList(), index.at(6))
        assertEquals(emptyList(), index.at(99))
    }

    @Test
    fun rangesAreClampedToTheTextAndEmptyOnesDropped() {
        val index = SegmentIndex.build(listOf(MarkRange(-5, 3, "x"), MarkRange(4, 4, "empty"), MarkRange(8, 99, "y")), length = 10)
        assertEquals(
            listOf(SegmentIndex.Segment(0, 3, listOf("x")), SegmentIndex.Segment(8, 10, listOf("y"))),
            index.segments(),
        )
        assertEquals(0, SegmentIndex.build(emptyList<MarkRange<String>>(), 5).size)
        assertTrue(SegmentIndex.build(listOf(MarkRange(0, 3, "x")), length = 0).segments().isEmpty())
    }

    @Test
    fun identicalRangesShareOneSegment() {
        val index = SegmentIndex.build(listOf(MarkRange(1, 3, "p"), MarkRange(1, 3, "q")), length = 5)
        assertEquals(listOf(SegmentIndex.Segment(1, 3, listOf("p", "q"))), index.segments())
    }
}
