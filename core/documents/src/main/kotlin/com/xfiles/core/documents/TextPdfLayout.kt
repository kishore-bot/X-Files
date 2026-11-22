package com.xfiles.core.documents

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import kotlin.math.ceil

/**
 * Text flowed for one style. The PDF and the preview both draw through [draw], at
 * different canvas scales, so they can't disagree. Units are points.
 * Not thread-safe: built and drawn on the conversion lane.
 */
internal class TextPdfLayout(val text: String, val style: TextPdfStyle) {
    val box: TextPageBox = style.contentBox()
    private val template = style.template
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = this@TextPdfLayout.style.textSizePt.coerceIn(TextPdfStyle.MIN_TEXT_SIZE, TextPdfStyle.MAX_TEXT_SIZE)
        color = this@TextPdfLayout.style.textArgb
        isSubpixelText = true
        typeface = Typeface.create(
            when (this@TextPdfLayout.style.font) {
                TextPdfFont.Serif -> Typeface.SERIF
                TextPdfFont.Sans -> Typeface.SANS_SERIF
                TextPdfFont.Mono -> Typeface.MONOSPACE
            },
            if (template.boldBody) Typeface.BOLD else Typeface.NORMAL,
        )
    }
    private val alignment = when (style.alignment) {
        TextPdfAlignment.Start -> Layout.Alignment.ALIGN_NORMAL
        TextPdfAlignment.Center -> Layout.Alignment.ALIGN_CENTER
        TextPdfAlignment.End -> Layout.Alignment.ALIGN_OPPOSITE
    }
    private val formatted = TextPdfFormatter.format(text, template)
    private val styled = SpannableString(formatted.text).apply {
        for (run in formatted.runs) {
            val scale = if (run.kind == TextPdfRunKind.Title) 1.7f else 1.2f
            setSpan(RelativeSizeSpan(scale), run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(style.accentArgb), run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    private val layout = lines(0, styled.length, box.width, null)

    /** Which lines hold the end of a heading, for the rule under it. */
    private val headingEnds = BooleanArray(layout.lineCount)
    val pages: List<TextPageSlice>

    init {
        val keep = BooleanArray(layout.lineCount)
        for (run in formatted.runs) {
            val lastLine = layout.getLineForOffset(run.end - 1)
            for (line in layout.getLineForOffset(run.start)..lastLine) keep[line] = true
            headingEnds[lastLine] = true
        }
        val boxes = List(layout.lineCount) { line ->
            val end = layout.getLineEnd(line)
            TextLineBox(
                height = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat(),
                endsParagraph = end > 0 && styled[end - 1] == '\n',
                blank = isBlankLine(line),
                keepWithNext = keep[line],
            )
        }
        pages = TextPaginator.paginate(boxes, box.height.toFloat(), paint.textSize * template.paragraphGap)
    }

    private fun lines(start: Int, end: Int, width: Int, direction: Int?): StaticLayout {
        val builder = StaticLayout.Builder.obtain(styled, start, end, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, template.lineSpacing)
            // A hyphen added by the line breaker isn't in the text, so drawLine would lose it.
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(true)
        if (direction != null) {
            builder.setTextDirection(if (direction == Layout.DIR_RIGHT_TO_LEFT) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
        }
        return builder.build()
    }

    private fun isBlankLine(line: Int): Boolean {
        for (index in layout.getLineStart(line) until layout.getLineEnd(line)) {
            if (!styled[index].isWhitespace()) return false
        }
        return true
    }

    /** One page as an upright bitmap [widthPx] wide. */
    fun render(pageIndex: Int, widthPx: Int): Bitmap {
        val scale = widthPx.toFloat() / box.pageWidth
        val bitmap = Bitmap.createBitmap(widthPx, ceil(box.pageHeight * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        draw(canvas, pageIndex)
        return bitmap
    }

    fun draw(canvas: Canvas, pageIndex: Int) {
        val page = pages[pageIndex]
        val textTop = box.top + TextPaginator.verticalOffset(page.usedHeight, box.height, template.centerVertically)
        val shapes = Paint(Paint.ANTI_ALIAS_FLAG)
        shapes.color = style.backgroundArgb
        canvas.drawRect(0f, 0f, box.pageWidth.toFloat(), box.pageHeight.toFloat(), shapes)
        drawDecoration(canvas, shapes, page, textTop)
        for (line in page.first until page.end) {
            val top = textTop + page.tops[line - page.first]
            if (template.ruleUnderHeadings && headingEnds[line]) {
                val bottom = top + layout.getLineBottom(line) - layout.getLineTop(line)
                shapes.color = style.accentArgb
                canvas.drawRect(box.left.toFloat(), bottom - 1f, (box.left + box.width).toFloat(), bottom, shapes)
            }
            if (!isBlankLine(line)) drawLine(canvas, line, top)
        }
    }

    /**
     * Each line is drawn on its own. The usual way, clipping one big layout to
     * the page, also sends neighbouring lines to the canvas, because Layout picks
     * its lines from rounded-out clip bounds. In a PDF those lines would be
     * invisible yet still found by search and copied.
     */
    private fun drawLine(canvas: Canvas, line: Int, top: Float) {
        val start = layout.getLineStart(line)
        var end = layout.getLineEnd(line)
        if (end > start && styled[end - 1] == '\n') end--
        val direction = layout.getParagraphDirection(line)
        var single = lines(start, end, box.width, direction)
        var nudge = 0f
        if (single.lineCount > 1) {
            // Shaped alone, the line came out a hair wider. Allow for that and keep it where it belongs.
            single = lines(start, end, box.width + WRAP_SLACK, direction)
            nudge = when {
                alignment == Layout.Alignment.ALIGN_CENTER -> -WRAP_SLACK / 2f
                (alignment == Layout.Alignment.ALIGN_OPPOSITE) == (direction == Layout.DIR_LEFT_TO_RIGHT) -> -WRAP_SLACK.toFloat()
                else -> 0f
            }
        }
        val baseline = top + layout.getLineBaseline(line) - layout.getLineTop(line)
        canvas.save()
        canvas.translate(box.left + nudge, baseline - single.getLineBaseline(0))
        single.draw(canvas)
        canvas.restore()
    }

    private fun drawDecoration(canvas: Canvas, shapes: Paint, page: TextPageSlice, textTop: Float) {
        val pageWidth = box.pageWidth.toFloat()
        val pageHeight = box.pageHeight.toFloat()
        val inset = style.marginPt.coerceAtLeast(0f) / 2
        shapes.color = style.accentArgb
        when (template.decoration) {
            TextPdfDecoration.None -> Unit
            TextPdfDecoration.AccentBars -> {
                val barLeft = (pageWidth - ACCENT_BAR_WIDTH) / 2
                val above = textTop - 20f
                val below = textTop + page.usedHeight + 16f
                canvas.drawRect(barLeft, above, barLeft + ACCENT_BAR_WIDTH, above + 4f, shapes)
                canvas.drawRect(barLeft, below, barLeft + ACCENT_BAR_WIDTH, below + 4f, shapes)
            }
            TextPdfDecoration.MarginRule -> {
                val x = box.left - TextPdfStyle.MARGIN_RULE_INSET.toFloat()
                canvas.drawRect(x, 0f, x + 2f, pageHeight, shapes)
            }
            TextPdfDecoration.RuledLines -> drawRuledLines(canvas, shapes, page, textTop)
            TextPdfDecoration.TopBand -> canvas.drawRect(0f, 0f, pageWidth, TextPdfStyle.TOP_BAND_HEIGHT.toFloat(), shapes)
            TextPdfDecoration.Frame -> {
                shapes.style = Paint.Style.STROKE
                shapes.strokeWidth = 3f
                canvas.drawRect(inset, inset, pageWidth - inset, pageHeight - inset, shapes)
                shapes.style = Paint.Style.FILL
            }
            TextPdfDecoration.Panel -> {
                shapes.color = 0xB3FFFFFF.toInt()
                canvas.drawRoundRect(RectF(inset, inset, pageWidth - inset, pageHeight - inset), 24f, 24f, shapes)
            }
        }
    }

    /** A rule under each line, then on down the page at the body text's spacing, like a notebook. */
    private fun drawRuledLines(canvas: Canvas, shapes: Paint, page: TextPageSlice, textTop: Float) {
        shapes.color = (style.accentArgb and 0x00FFFFFF) or 0x59000000
        val left = box.left.toFloat()
        val right = left + box.width
        var y = textTop
        for (line in page.first until page.end) {
            y = textTop + page.tops[line - page.first] + layout.getLineBottom(line) - layout.getLineTop(line)
            canvas.drawRect(left, y - 1f, right, y, shapes)
        }
        val spacing = paint.getFontMetrics(null) * template.lineSpacing
        y += spacing
        while (y <= box.top + box.height) {
            canvas.drawRect(left, y - 1f, right, y, shapes)
            y += spacing
        }
    }

    private companion object {
        const val WRAP_SLACK = 8
        const val ACCENT_BAR_WIDTH = 64f
    }
}
