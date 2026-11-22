package com.xfiles.core.documents

import kotlin.math.max
import kotlin.math.roundToInt

/** Longest text the live preview lays out; longer drafts still export, just without a preview. */
const val MAX_PREVIEW_TEXT_CHARS = 200_000

enum class TextPdfAlignment(val label: String) { Start("Start"), Center("Center"), End("End") }

enum class TextPdfFont(val label: String) { Serif("Serif"), Sans("Sans"), Mono("Mono") }

/** What a template draws around the text. Only shapes: templates never add words the user didn't type. */
enum class TextPdfDecoration { None, AccentBars, MarginRule, RuledLines, TopBand, Frame, Panel }

/** A fixed recipe. The same text and style always give the same pages. */
enum class TextPdfTemplate(
    val label: String,
    val font: TextPdfFont,
    val alignment: TextPdfAlignment,
    val textSizePt: Float,
    val marginPt: Float,
    val accentArgb: Int,
    val backgroundArgb: Int,
    val decoration: TextPdfDecoration,
    /** The first line, if short, becomes a large title. */
    val titleFirstLine: Boolean = false,
    /** Finds headings ("# x", "x:", "ALL CAPS") and turns "- " into bullets. */
    val structured: Boolean = false,
    val ruleUnderHeadings: Boolean = false,
    val boldBody: Boolean = false,
    val centerVertically: Boolean = false,
    val lineSpacing: Float = 1.3f,
    /** Space after a paragraph, as a fraction of the text size. */
    val paragraphGap: Float = 0.5f,
) {
    QuoteCard(
        "Quote card", TextPdfFont.Serif, TextPdfAlignment.Center, 28f, 72f,
        0xFFE60023.toInt(), 0xFFFFF7F2.toInt(), TextPdfDecoration.AccentBars,
        centerVertically = true, lineSpacing = 1.35f, paragraphGap = 0.8f,
    ),
    MinimalLetter(
        "Minimal letter", TextPdfFont.Serif, TextPdfAlignment.Start, 12f, 72f,
        0xFF3A3A3A.toInt(), 0xFFFFFFFF.toInt(), TextPdfDecoration.None,
        lineSpacing = 1.4f, paragraphGap = 0.9f,
    ),
    StudyNotes(
        "Study notes", TextPdfFont.Sans, TextPdfAlignment.Start, 12f, 54f,
        0xFF1F6FB2.toInt(), 0xFFFFFDF5.toInt(), TextPdfDecoration.MarginRule,
        titleFirstLine = true, structured = true,
    ),
    Journal(
        "Journal", TextPdfFont.Serif, TextPdfAlignment.Start, 13f, 60f,
        0xFF8A6D3B.toInt(), 0xFFFBF6EA.toInt(), TextPdfDecoration.RuledLines,
        lineSpacing = 1.6f, paragraphGap = 0f,
    ),
    ProfessionalMemo(
        "Professional memo", TextPdfFont.Sans, TextPdfAlignment.Start, 11f, 64f,
        0xFF1E3A5F.toInt(), 0xFFFFFFFF.toInt(), TextPdfDecoration.TopBand,
        titleFirstLine = true, ruleUnderHeadings = true, lineSpacing = 1.35f, paragraphGap = 0.8f,
    ),
    DarkPoster(
        "Dark poster", TextPdfFont.Sans, TextPdfAlignment.Center, 34f, 56f,
        0xFFFF4564.toInt(), 0xFF14121A.toInt(), TextPdfDecoration.Frame,
        boldBody = true, centerVertically = true, lineSpacing = 1.2f, paragraphGap = 0.6f,
    ),
    Pastel(
        "Pastel", TextPdfFont.Sans, TextPdfAlignment.Start, 15f, 64f,
        0xFFB5478F.toInt(), 0xFFFDE7F0.toInt(), TextPdfDecoration.Panel,
        titleFirstLine = true, lineSpacing = 1.4f, paragraphGap = 0.7f,
    ),
    ResumeStyle(
        "Resume style", TextPdfFont.Sans, TextPdfAlignment.Start, 10.5f, 48f,
        0xFF0F766E.toInt(), 0xFFFFFFFF.toInt(), TextPdfDecoration.None,
        titleFirstLine = true, structured = true, ruleUnderHeadings = true, paragraphGap = 0.4f,
    ),
}

/** Everything that decides how text looks. Start from [of]; the controls then override single fields. */
data class TextPdfStyle(
    val template: TextPdfTemplate,
    val alignment: TextPdfAlignment,
    val font: TextPdfFont,
    val textSizePt: Float,
    val paper: PdfPaper,
    val marginPt: Float,
    val accentArgb: Int,
    val backgroundArgb: Int,
) {
    /** Follows the background, so no choice of background makes the text unreadable. */
    val textArgb: Int get() = if (relativeLuminance(backgroundArgb) < 0.4f) 0xFFF5F2F7.toInt() else 0xFF17121A.toInt()

    /** The page and its text area, in whole points. */
    fun contentBox(): TextPageBox {
        val pageWidth = paper.width.roundToInt()
        val pageHeight = paper.height.roundToInt()
        val margin = marginPt.coerceIn(0f, pageWidth / 3f).toInt()
        val extraLeft = if (template.decoration == TextPdfDecoration.MarginRule) MARGIN_RULE_INSET else 0
        val extraTop = when (template.decoration) {
            TextPdfDecoration.TopBand -> TOP_BAND_HEIGHT
            TextPdfDecoration.AccentBars -> ACCENT_BAR_INSET
            else -> 0
        }
        val extraBottom = if (template.decoration == TextPdfDecoration.AccentBars) ACCENT_BAR_INSET else 0
        return TextPageBox(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            left = margin + extraLeft,
            top = margin + extraTop,
            width = pageWidth - margin * 2 - extraLeft,
            height = pageHeight - margin * 2 - extraTop - extraBottom,
        )
    }

    companion object {
        const val MIN_TEXT_SIZE = 8f
        const val MAX_TEXT_SIZE = 72f
        const val MIN_MARGIN = 18f
        const val MAX_MARGIN = 108f
        const val MARGIN_RULE_INSET = 14
        const val TOP_BAND_HEIGHT = 14
        const val ACCENT_BAR_INSET = 28

        fun of(template: TextPdfTemplate, paper: PdfPaper = PdfPaper.A4) = TextPdfStyle(
            template = template,
            alignment = template.alignment,
            font = template.font,
            textSizePt = template.textSizePt,
            paper = paper,
            marginPt = template.marginPt,
            accentArgb = template.accentArgb,
            backgroundArgb = template.backgroundArgb,
        )
    }
}

/** A page and its text area, in points. */
data class TextPageBox(val pageWidth: Int, val pageHeight: Int, val left: Int, val top: Int, val width: Int, val height: Int)

/** Rough perceived brightness, 0 (black) to 1 (white); enough to choose between dark and light text. */
internal fun relativeLuminance(argb: Int): Float {
    val red = (argb shr 16 and 0xFF) / 255f
    val green = (argb shr 8 and 0xFF) / 255f
    val blue = (argb and 0xFF) / 255f
    return 0.2126f * red * red + 0.7152f * green * green + 0.0722f * blue * blue
}

enum class TextPdfRunKind { Title, Heading }

/** A stretch of [FormattedTextPdf.text] (start inclusive, end exclusive) with heading styling. */
data class TextPdfRun(val start: Int, val end: Int, val kind: TextPdfRunKind)

class FormattedTextPdf(val text: String, val runs: List<TextPdfRun>)

/** Applies a template's fixed text rules. Runs are in order and don't overlap. */
object TextPdfFormatter {
    private const val MAX_TITLE_CHARS = 120
    private const val MAX_HEADING_CHARS = 60

    fun format(text: String, template: TextPdfTemplate): FormattedTextPdf {
        val out = StringBuilder(text.length)
        val runs = mutableListOf<TextPdfRun>()
        var titleDone = !template.titleFirstLine
        val lines = clean(text).split('\n')
        lines.forEachIndexed { index, raw ->
            val start = out.length
            var line = raw
            var kind: TextPdfRunKind? = null
            if (raw.isNotBlank() && !titleDone) {
                titleDone = true
                if (raw.length <= MAX_TITLE_CHARS) {
                    kind = TextPdfRunKind.Title
                    line = raw.trimStart('#', ' ')
                }
            }
            if (kind == null && template.structured) {
                val trimmed = raw.trim()
                when {
                    trimmed.startsWith("#") && trimmed.trimStart('#').startsWith(" ") -> {
                        kind = TextPdfRunKind.Heading
                        line = trimmed.trimStart('#').trim()
                    }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> line = "• " + trimmed.substring(2)
                    looksLikeHeading(trimmed) -> kind = TextPdfRunKind.Heading
                }
            }
            out.append(line)
            if (kind != null && line.isNotEmpty()) runs += TextPdfRun(start, out.length, kind)
            if (index < lines.lastIndex) out.append('\n')
        }
        return FormattedTextPdf(out.toString(), runs)
    }

    /** Line ends become \n and other control characters go, since they would draw as boxes. */
    private fun clean(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        return buildString(unified.length) {
            for (char in unified) {
                if (char == '\n' || char == '\t' || !char.isISOControl()) append(char)
            }
        }
    }

    private fun looksLikeHeading(line: String): Boolean {
        if (line.isEmpty() || line.length > MAX_HEADING_CHARS) return false
        if (line.endsWith(':') || line.endsWith('：')) return true
        val letters = line.filter(Char::isLetter)
        return letters.length >= 2 && letters.all(Char::isUpperCase)
    }
}

/** One laid-out line, measured by the platform. */
data class TextLineBox(val height: Float, val endsParagraph: Boolean, val blank: Boolean, val keepWithNext: Boolean)

/** Lines [first] until [end], where [tops] holds each line's offset from the top of the text area. */
class TextPageSlice(val first: Int, val end: Int, val tops: FloatArray, val usedHeight: Float)

/**
 * Breaks lines into pages. Every line lands on one page, in order and whole,
 * except blank lines at the top of a continued page, which are dropped. A
 * heading isn't left at the bottom of a page without what follows it. A line
 * taller than the page gets a page to itself. There is always at least one page.
 */
object TextPaginator {
    fun paginate(lines: List<TextLineBox>, pageHeight: Float, paragraphGap: Float): List<TextPageSlice> {
        val pages = mutableListOf<TextPageSlice>()
        var next = 0
        while (next < lines.size) {
            if (pages.isNotEmpty()) {
                while (next < lines.size && lines[next].blank) next++
                if (next == lines.size) break
            }
            val first = next
            val tops = ArrayList<Float>()
            var y = 0f
            while (next < lines.size) {
                val line = lines[next]
                if (next > first && y + line.height > pageHeight) break
                tops += y
                y += line.height
                if (line.endsParagraph) y += paragraphGap
                next++
            }
            if (next < lines.size) {
                while (next - 1 > first && lines[next - 1].keepWithNext) next--
            }
            val kept = next - first
            pages += TextPageSlice(first, next, FloatArray(kept) { tops[it] }, tops[kept - 1] + lines[next - 1].height)
        }
        return if (pages.isEmpty()) listOf(TextPageSlice(0, 0, FloatArray(0), 0f)) else pages
    }

    /** Where the text starts inside its area, for templates that centre it. */
    fun verticalOffset(usedHeight: Float, areaHeight: Int, center: Boolean): Float =
        if (center) max(0f, (areaHeight - usedHeight) / 2) else 0f
}
