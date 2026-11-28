package com.xfiles.documents.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookAnnotationsTest {
    private fun highlight(id: String, block: Int, start: Int, end: Int, color: HighlightColor = HighlightColor.Yellow) =
        Highlight(id, block, start, end, color, "quote $id", createdAt = id.hashCode().toLong())

    @Test
    fun sameRangeReplacesItsHighlightSoOneRangeKeepsOneColour() {
        val first = BookAnnotations().withHighlight(highlight("a", 3, 0, 5, HighlightColor.Yellow))
        val second = first.withHighlight(highlight("b", 3, 0, 5, HighlightColor.Pink))
        assertEquals(listOf("b"), second.highlights.map { it.id })
        assertEquals(HighlightColor.Pink, second.highlights.single().color)
    }

    @Test
    fun overlappingHighlightsAreAllKept() {
        val annotations = BookAnnotations()
            .withHighlight(highlight("a", 3, 0, 5))
            .withHighlight(highlight("b", 3, 2, 8, HighlightColor.Blue))
        val marks = annotations.marksByBlock().getValue(3)
        val index = SegmentIndex.build(marks, length = 10)
        assertEquals(
            listOf(TextMark.Highlighted("a", HighlightColor.Yellow), TextMark.Highlighted("b", HighlightColor.Blue)),
            index.at(3),
        )
    }

    @Test
    fun bookmarkTogglesOnItsBlock() {
        val on = BookAnnotations().withBookmarkToggled(block = 7, label = "Chapter 2", id = "m1", now = 1)
        assertTrue(on.isBookmarked(7))
        assertFalse(on.withBookmarkToggled(7, "again", "m2", 2).isBookmarked(7))
    }

    @Test
    fun notebookIsInReadingOrderAcrossKinds() {
        val annotations = BookAnnotations(
            highlights = listOf(highlight("h", 5, 10, 14)),
            notes = listOf(BookNote("n", 2, 0, 3, "my thought", "the quote", createdAt = 9)),
            bookmarks = listOf(Bookmark("b", 5, "Here", createdAt = 4)),
        )
        val entries = annotations.notebook()
        assertEquals(listOf(NotebookKind.Note, NotebookKind.Bookmark, NotebookKind.Highlight), entries.map { it.kind })
        assertEquals("my thought", entries.first().detail)
    }

    @Test
    fun withoutRemovesById() {
        val annotations = BookAnnotations().withHighlight(highlight("a", 1, 0, 1)).withNote(BookNote("n", 1, 0, 1, "t", "q", 0))
        val cleared = annotations.withoutHighlight("a").withoutNote("n")
        assertTrue(cleared.highlights.isEmpty() && cleared.notes.isEmpty())
    }

    @Test
    fun positionIsNeverNegative() {
        assertEquals(0, BookAnnotations().withPosition(-4).position)
        assertEquals(12, BookAnnotations().withPosition(12).position)
    }

    @Test
    fun jsonRoundTripsAndUnreadableTextFallsBackToEmpty() {
        val annotations = BookAnnotations(
            highlights = listOf(highlight("a", 1, 0, 4, HighlightColor.Green)),
            notes = listOf(BookNote("n", 1, 2, 6, "note \"quoted\"", "quote", 3)),
            bookmarks = listOf(Bookmark("b", 1, "label", 5)),
            position = 4,
        )
        assertEquals(annotations, BookAnnotations.decodeOrEmpty(BookAnnotations.encode(annotations)))
        assertEquals(BookAnnotations(), BookAnnotations.decodeOrEmpty("{not json"))
    }

    @Test
    fun aBookSettingsOverrideIsStoredWithTheBookAndCanBeDropped() {
        val override = ReadingSettings(ReadingTheme.Dark, ReadingFont.Sans, 20, LineSpacing.Loose, PageMargin.Narrow)
        val overridden = BookAnnotations(position = 3).withSettings(override)
        assertEquals(override, BookAnnotations.decodeOrEmpty(BookAnnotations.encode(overridden)).settings)
        assertEquals(null, BookAnnotations.decodeOrEmpty(BookAnnotations.encode(overridden.withSettings(null))).settings)
        // Books saved before overrides existed have none.
        assertEquals(null, BookAnnotations.decodeOrEmpty("""{"position":4}""").settings)
        assertEquals(overridden, overridden.withSettings(override))
    }

    @Test
    fun listsAreCappedDroppingTheOldest() {
        var annotations = BookAnnotations()
        repeat(BookAnnotations.MAX_BOOKMARKS + 3) { annotations = annotations.withBookmarkToggled(it, "b$it", "id$it", it.toLong()) }
        assertEquals(BookAnnotations.MAX_BOOKMARKS, annotations.bookmarks.size)
        assertEquals(3, annotations.bookmarks.first().block)
    }
}
