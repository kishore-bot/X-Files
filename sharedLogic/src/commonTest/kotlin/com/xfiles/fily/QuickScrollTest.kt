package com.xfiles.fily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickScrollTest {
    private val scrolling = listOf("scroll_down", "scroll_up", "scroll_top", "scroll_bottom")

    private fun op(request: String) = QuickCommands.parse(request, scrolling)?.operation

    @Test
    fun naturalScrollPhrasesAreCaught() {
        assertEquals("scroll_down", op("scroll down"))
        assertEquals("scroll_down", op("Scroll down the page please"))
        assertEquals("scroll_down", op("scroll a bit more"))
        assertEquals("scroll_down", op("move down"))
        assertEquals("scroll_down", op("Fily, scroll the document"))
        assertEquals("scroll_up", op("scroll up a little"))
        assertEquals("scroll_up", op("move up the page"))
        assertEquals("scroll_top", op("scroll all the way up"))
        assertEquals("scroll_top", op("take me to the top"))
        assertEquals("scroll_bottom", op("take me to the end of the file"))
        assertEquals("scroll_bottom", op("scroll to the bottom"))
    }

    @Test
    fun questionsAndOtherRequestsGoToTheModel() {
        assertNull(op("why can't you scroll the table"))
        assertNull(op("go back"))
        assertNull(op("scroll down and summarise the second chapter for me in detail"))
        assertNull(op("skip ahead"))
    }

    @Test
    fun nothingWhenTheViewerCannotScroll() {
        assertNull(QuickCommands.parse("scroll down the page", listOf("next_page")))
    }
}
