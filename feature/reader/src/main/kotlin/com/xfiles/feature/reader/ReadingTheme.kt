package com.xfiles.feature.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Page look for text-based viewers (plain text, source code, Markdown source). The choice is per
 * reader session, lives in ReaderScreen and never changes the app theme. [Standard] follows the app.
 */
enum class ReadingTheme {
    Standard,
    Dark,
    Sepia;

    /** The next option in the reader menu's cycle. */
    fun next(): ReadingTheme = entries[(ordinal + 1) % entries.size]

    /** Page colours, or null for [Standard] so callers keep the app's own colours. */
    fun palette(): ReadingPalette? = when (this) {
        Standard -> null
        Dark -> ReadingPalette(page = Color(0xFF121214), text = Color(0xFFE4E2DE), secondary = Color(0xFF9B9A96), syntaxDark = true)
        Sepia -> ReadingPalette(page = Color(0xFFF4ECD8), text = Color(0xFF3B2F22), secondary = Color(0xFF7A6A55), syntaxDark = false)
    }
}

@Immutable
data class ReadingPalette(
    val page: Color,
    val text: Color,
    val secondary: Color,
    /** Whether syntax colours should use the dark-page set. */
    val syntaxDark: Boolean,
)
