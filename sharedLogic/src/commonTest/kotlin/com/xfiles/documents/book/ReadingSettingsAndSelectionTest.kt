package com.xfiles.documents.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingSettingsAndSelectionTest {
    @Test
    fun textSizeIsClampedAndThemeLeavesTheLayoutAlone() {
        assertEquals(ReadingSettings.MAX_TEXT_SIZE, ReadingSettings().withTextSize(99).textSizeSp)
        assertEquals(ReadingSettings.MIN_TEXT_SIZE, ReadingSettings().withTextSize(1).textSizeSp)
        val dark = ReadingSettings().copy(theme = ReadingTheme.Dark)
        assertEquals(ReadingSettings().layout, dark.layout)
    }

    @Test
    fun settingsRoundTripAndUnreadableOnesFallBackToDefaults() {
        val settings = ReadingSettings(ReadingTheme.Sepia, ReadingFont.Mono, 22, LineSpacing.Loose, PageMargin.Wide)
        assertEquals(settings, ReadingSettings.decodeOrDefault(ReadingSettings.encode(settings)))
        assertEquals(ReadingSettings(), ReadingSettings.decodeOrDefault(null))
        assertEquals(ReadingSettings(), ReadingSettings.decodeOrDefault("garbage"))
    }

    @Test
    fun lruEvictsTheLeastRecentlyUsedEntry() {
        val cache = LruCache<String, Int>(capacity = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        assertEquals(1, cache.get("a"))
        cache.put("c", 3)
        assertEquals(null, cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
        assertEquals(2, cache.size)
    }

    @Test
    fun wordAtOffsetCoversTheWordAndApostrophes() {
        val text = "Don't stop, Ana."
        assertEquals(0 until 5, wordRangeAt(text, 2))
        assertEquals(5 until 6, wordRangeAt(text, 5))
        assertEquals(6 until 10, wordRangeAt(text, 8))
        assertEquals(12 until 15, wordRangeAt(text, 14))
        assertNull(wordRangeAt("", 0))
        assertEquals(4 until 5, wordRangeAt("abcd!", 99))
    }

    @Test
    fun dragExtendsTheWordSelectionInEitherDirection() {
        val text = "one two three"
        assertEquals(0 until 7, extendWordSelection(0 until 3, focusOffset = 5, text = text))
        assertEquals(4 until 13, extendWordSelection(4 until 7, focusOffset = 12, text = text))
        // A drag back over a word inside the selection never shrinks it.
        assertEquals(0 until 7, extendWordSelection(0 until 7, focusOffset = 4, text = text))
    }

    @Test
    fun orderedSelectionIsClampedAndEmptyWhenNothingIsCovered() {
        assertEquals(2 until 5, orderedSelection(5, 2, length = 10))
        assertEquals(0 until 4, orderedSelection(-3, 4, length = 10))
        assertNull(orderedSelection(3, 3, length = 10))
        assertNull(orderedSelection(12, 15, length = 10))
    }
}
