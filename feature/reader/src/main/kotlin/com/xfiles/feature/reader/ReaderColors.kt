package com.xfiles.feature.reader

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

@Immutable
internal data class ReaderColors(
    val page: Color,
    val text: Color,
    val secondary: Color,
    val link: Color,
    val grid: Color,
    val header: Color,
    val selection: Color,
    val match: Color,
    val currentMatch: Color,
    val matchText: Color,
    val dark: Boolean = false,
) {
    fun selectionColors() = TextSelectionColors(selection, selection.copy(alpha = 0.28f))
}

private val LightReader = ReaderColors(
    page = Color(0xFFFFFCFD), text = Color(0xFF241922), secondary = Color(0xFF665660),
    link = Color(0xFF174C9C), grid = Color(0xFF8B7B88), header = Color(0xFFF4E9EF),
    selection = Color(0xFF94405B), match = Color(0xFFFFE6A7), currentMatch = Color(0xFFFFC852),
    matchText = Color(0xFF2C1B00),
)

private val DarkReader = ReaderColors(
    page = Color(0xFF171419), text = Color(0xFFF2EBF0), secondary = Color(0xFFC1B3BD),
    link = Color(0xFFAAC9FF), grid = Color(0xFF82717F), header = Color(0xFF312631),
    selection = Color(0xFFFFA5BE), match = Color(0xFF654400), currentMatch = Color(0xFFFFC75C),
    matchText = Color(0xFF231500), dark = true,
)

private val SepiaReader = LightReader.copy(
    page = Color(0xFFF4ECD8), text = Color(0xFF3B2F22), secondary = Color(0xFF67523B),
    link = Color(0xFF26477E), grid = Color(0xFF827057), header = Color(0xFFE8DAC0),
    selection = Color(0xFF79442F),
)

internal val LocalReaderColors = compositionLocalOf { LightReader }

internal fun PageStyle.readerColors(darkApp: Boolean): ReaderColors = when (this) {
    PageStyle.Standard -> if (darkApp) DarkReader else LightReader
    PageStyle.Original -> LightReader.copy(page = Color.White, text = Color(0xFF161616))
    PageStyle.Dark -> DarkReader
    PageStyle.Sepia -> SepiaReader
}

/** Keep authored ink when it is readable. Otherwise move its tone toward a readable tone. */
internal fun readableInk(ink: Color, background: Color, minimum: Float = 4.5f): Color {
    val base = background.luminance()
    fun contrast(color: Color): Float {
        val value = color.compositeOver(background).luminance()
        return (maxOf(value, base) + 0.05f) / (minOf(value, base) + 0.05f)
    }
    if (contrast(ink) >= minimum) return ink
    val endpoint = if (contrast(Color.Black) >= contrast(Color.White)) Color.Black else Color.White
    var low = 0f
    var high = 1f
    repeat(8) {
        val mid = (low + high) / 2f
        if (contrast(lerp(ink, endpoint, mid)) >= minimum) high = mid else low = mid
    }
    return lerp(ink, endpoint, high)
}
