package com.xfiles.feature.reader

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import com.xfiles.documents.book.TextAlignment
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.documents.book.HighlightColor
import com.xfiles.documents.book.PageIndex
import com.xfiles.documents.book.ReadingLayout
import com.xfiles.documents.book.ReadingFont
import com.xfiles.documents.book.ReadingSettings
import com.xfiles.documents.book.ReadingTheme
import com.xfiles.documents.book.cutPage
import com.xfiles.documents.structure.ContentBlock
import kotlin.math.min

/** Colours of one reading theme. [onDark] tells the rich-text colouring which colours would vanish. */
internal class BookPalette(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val onDark: Boolean,
    val selection: Color,
    val codeFill: Color,
    /** Colour of links, so they read as links on every theme. */
    val link: Color = EzColors.Primary,
)

internal fun bookPalette(theme: ReadingTheme): BookPalette = when (theme) {
    ReadingTheme.Light -> BookPalette(
        background = Color(0xFFFFFFFF), text = Color(0xFF1B1B1B), secondaryText = Color(0xFF625B66),
        onDark = false, selection = Color(0x663D7BFF), codeFill = Color(0xFFF2F2F5),
    )
    ReadingTheme.Sepia -> BookPalette(
        background = Color(0xFFF4ECD8), text = Color(0xFF4A3B2A), secondaryText = Color(0xFF7A6A58),
        onDark = false, selection = Color(0x665B7BC0), codeFill = Color(0xFFEADFC6),
    )
    ReadingTheme.Green -> BookPalette(
        background = Color(0xFFCFE5CB), text = Color(0xFF1D2B1D), secondaryText = Color(0xFF4A5E4A),
        onDark = false, selection = Color(0x663D7BFF), codeFill = Color(0xFFBED6B8),
    )
    ReadingTheme.Dark -> BookPalette(
        background = Color(0xFF1B1B1F), text = Color(0xFFE6E1E5), secondaryText = Color(0xFFB8B2BC),
        onDark = true, selection = Color(0x665B7BC0), codeFill = Color(0xFF2A2A30),
    )
    ReadingTheme.Black -> BookPalette(
        background = Color(0xFF000000), text = Color(0xFFD2D2D2), secondaryText = Color(0xFF8A8A8A),
        onDark = true, selection = Color(0x6683A6FF), codeFill = Color(0xFF111114),
    )
}

/** The paint for a highlight: translucent, so the text stays readable on every theme. */
internal fun HighlightColor.paint(onDark: Boolean): Color =
    swatch().copy(alpha = if (onDark) 0.38f else 0.55f)

/** Text styles for one settings-and-theme combination. */
internal class BookTypography(
    val body: TextStyle,
    private val headings: List<TextStyle>,
    val quote: TextStyle,
    val code: TextStyle,
) {
    fun heading(level: Int): TextStyle = headings[(level - 1).coerceIn(0, headings.lastIndex)]
}

/** Caladea, the metric twin of Cambria, bundled as in the EzFiles reader (SIL Open Font License). */
private val CaladeaFamily = FontFamily(
    Font(R.font.caladea_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.caladea_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.caladea_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.caladea_bolditalic, FontWeight.Bold, FontStyle.Italic),
)

internal fun bookTypography(settings: ReadingSettings, palette: BookPalette): BookTypography {
    val size = settings.textSizeSp.toFloat()
    val family = when (settings.font) {
        // The book's own face is not read from its styles here, so it takes the serif a book usually uses.
        ReadingFont.Original, ReadingFont.Serif -> FontFamily.Serif
        ReadingFont.Caladea -> CaladeaFamily
        ReadingFont.Sans -> FontFamily.SansSerif
        ReadingFont.Mono -> FontFamily.Monospace
    }
    val justify = if (settings.alignment == TextAlignment.Justified) TextAlign.Justify else null
    val multiplier = settings.lineSpacing.multiplier
    // "Book's own" line spacing leaves the height unset, so the font's own line height applies.
    fun style(scale: Float, bold: Boolean = false, italic: Boolean = false, mono: Boolean = false, justified: Boolean = false) = TextStyle(
        fontFamily = if (mono) FontFamily.Monospace else family,
        fontSize = (size * scale).sp,
        lineHeight = if (multiplier > 0f) (size * scale * multiplier).sp else TextUnit.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textAlign = (if (justified) justify else null) ?: TextAlign.Unspecified,
        color = palette.text,
    )
    return BookTypography(
        body = style(1f, justified = true),
        headings = listOf(style(1.6f, bold = true), style(1.35f, bold = true), style(1.15f, bold = true)),
        quote = style(1f, italic = true, justified = true),
        code = style(0.85f, mono = true),
    )
}

/** Identifies one pagination: the book, the layout settings, and the page's size in pixels. */
internal data class BookLayoutKey(val book: String, val layout: ReadingLayout, val widthPx: Int, val pageHeightPx: Int)

/**
 * One book paginated at one [BookLayoutKey]. Pages are cut on demand by [cutPages]; each block's
 * height is measured once and kept, so turning pages or jumping never re-measures what is known.
 */
internal class BookLayout(val blocks: List<ContentBlock>, private val pageHeightPx: Int, private val gapPx: Int) {
    val index = PageIndex(blocks.size)
    private val heights = IntArray(blocks.size) { UNKNOWN }

    /** Changes after pages are cut, so composables that read the page count recompose. */
    var revision: Int by mutableIntStateOf(0)
        private set

    /** Cuts up to [pages] more pages, measuring blocks with [measure]. Returns false when the book is fully paged. */
    fun cutPages(pages: Int, measure: (ContentBlock) -> Int): Boolean {
        var progressed = false
        repeat(pages) {
            val cut = index.extendOnePage { start ->
                cutPage(start, blocks.size, pageHeightPx, gapPx) { block -> heightOf(block, measure) }
            }
            progressed = progressed || cut
        }
        if (progressed) revision++
        return progressed
    }

    private fun heightOf(block: Int, measure: (ContentBlock) -> Int): Int {
        val known = heights[block]
        if (known != UNKNOWN) return known
        return measure(blocks[block]).also { heights[block] = it }
    }

    private companion object {
        const val UNKNOWN = -1
    }
}

/**
 * Measures a block's height in pixels for a page [widthPx] wide, with the same text styles the
 * block is drawn with, so a page cut here fits what is drawn. Images and tables use estimates.
 */
internal class BookMeasurer(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val typography: BookTypography,
    private val widthPx: Int,
    private val onDark: Boolean,
) {
    fun height(block: ContentBlock): Int = with(density) {
        val indent = 24.dp.roundToPx()
        when (block) {
            is ContentBlock.Heading -> lines(richText(block.text, block.spans, onDark), typography.heading(block.level), widthPx) + 8.dp.roundToPx()
            is ContentBlock.Paragraph -> lines(richText(block.text, block.spans, onDark), typography.body, widthPx)
            is ContentBlock.ListItem -> lines(
                richText(block.text, block.spans, onDark),
                typography.body,
                widthPx - (block.depth.coerceIn(0, 8) * 14 + 18).dp.roundToPx(),
            )
            is ContentBlock.Quote -> indent + lines(richText(block.text, block.spans, onDark), typography.quote, widthPx - indent)
            is ContentBlock.Code -> indent + lines(AnnotatedString(block.text), typography.code, widthPx - indent)
            is ContentBlock.Image -> imageHeight(block)  // resolved on the Density receiver of with(density)
            is ContentBlock.TableRow -> {
                // Same cell width and padding as BookTableRow draws, with the links' text as laid out there.
                val cellWidth = (BookCellWidth - 16.dp).roundToPx()
                var tallest = lines(AnnotatedString(" "), typography.body, cellWidth)
                for (column in block.cells.indices) {
                    val spans = block.cellSpans.getOrNull(column).orEmpty()
                    tallest = maxOf(tallest, lines(richText(block.cells[column], spans, onDark), typography.body, cellWidth))
                }
                16.dp.roundToPx() + tallest
            }
            ContentBlock.Divider -> 17.dp.roundToPx()
            is ContentBlock.Section -> lines(AnnotatedString(block.title), typography.heading(3), widthPx)
            else -> 0
        }
    }

    private fun Density.imageHeight(block: ContentBlock.Image): Int {
        val maxHeight = 440.dp.roundToPx()
        val width = block.width
        val height = block.height
        if (width == null || height == null || width <= 0f || height <= 0f) return min(maxHeight, 220.dp.roundToPx())
        return min(maxHeight, (widthPx * height / width).toInt())
    }

    private fun lines(text: AnnotatedString, style: TextStyle, maxWidth: Int): Int =
        measurer.measure(
            text = text,
            style = style,
            constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1)),
        ).size.height
}
