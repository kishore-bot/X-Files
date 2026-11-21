package com.xfiles.documents.book

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ReadingTheme { Light, Sepia, Dark, Black }

@Serializable
enum class ReadingFont { Serif, Sans, Mono }

/** Line height as a multiple of the text size. */
@Serializable
enum class LineSpacing(val multiplier: Float) { Compact(1.3f), Normal(1.55f), Loose(1.85f) }

/** Side margin of a page, in dp. */
@Serializable
enum class PageMargin(val dp: Int) { Narrow(12), Medium(20), Wide(32) }

/** The settings that decide how text breaks into lines. A change to any of them re-paginates the book. */
@Serializable
data class ReadingLayout(
    val font: ReadingFont,
    val textSizeSp: Int,
    val lineSpacing: LineSpacing,
    val margin: PageMargin,
)

/** The reader's "Aa" settings. Theme changes colours only; everything else is in [layout]. */
@Serializable
data class ReadingSettings(
    val theme: ReadingTheme = ReadingTheme.Light,
    val font: ReadingFont = ReadingFont.Serif,
    val textSizeSp: Int = DEFAULT_TEXT_SIZE,
    val lineSpacing: LineSpacing = LineSpacing.Normal,
    val margin: PageMargin = PageMargin.Medium,
) {
    val layout: ReadingLayout
        get() = ReadingLayout(font, textSizeSp, lineSpacing, margin)

    /** Clamps the text size into its range. */
    fun sanitized(): ReadingSettings = copy(textSizeSp = textSizeSp.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE))

    fun withTextSize(size: Int): ReadingSettings = copy(textSizeSp = size).sanitized()

    companion object {
        const val MIN_TEXT_SIZE = 12
        const val MAX_TEXT_SIZE = 30
        const val DEFAULT_TEXT_SIZE = 17

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(settings: ReadingSettings): String = json.encodeToString(ReadingSettings.serializer(), settings)

        /** The stored settings, or the defaults when [text] is missing or unreadable. */
        fun decodeOrDefault(text: String?): ReadingSettings {
            if (text.isNullOrBlank()) return ReadingSettings()
            return try {
                json.decodeFromString(ReadingSettings.serializer(), text).sanitized()
            } catch (error: IllegalArgumentException) {
                ReadingSettings()
            }
        }
    }
}
