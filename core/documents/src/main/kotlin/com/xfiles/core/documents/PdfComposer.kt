package com.xfiles.core.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import kotlin.math.min

/**
 * Lays text and pictures out as PDF pages with Android's [PdfDocument]. Pages are produced lazily from
 * [Sequence]s, so only one page's bitmap is alive at a time. Not thread-safe: callers run it on one lane.
 */
internal object PdfComposer {
    private const val MARGIN = 54f // 0.75 inch.

    /** Text in 11 pt on [paper]; returns the page count. [onPage] reports each finished page. */
    fun writeText(text: String, paper: PdfPaper, output: OutputStream, onPage: (Int) -> Unit = {}): Int {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val width = (paper.width - 2 * MARGIN).toInt()
        val height = paper.height - 2 * MARGIN
        val layout = StaticLayout.Builder.obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .setIncludePad(false)
            .build()
        val document = PdfDocument()
        try {
            var count = 0
            pageBreaks(layout, height).forEach { lines ->
                val top = layout.getLineTop(lines.first).toFloat()
                val bottom = layout.getLineBottom(lines.last).toFloat()
                val page = document.startPage(PdfDocument.PageInfo.Builder(paper.width.toInt(), paper.height.toInt(), ++count).create())
                page.canvas.apply {
                    translate(MARGIN, MARGIN - top)
                    clipRect(0f, top, width.toFloat(), bottom)
                    layout.draw(this)
                }
                document.finishPage(page)
                onPage(count)
            }
            document.writeTo(output)
            return count
        } finally {
            document.close()
        }
    }

    /** Groups the layout's lines into pages of at most [height] points; a page always holds one line. */
    fun pageBreaks(layout: Layout, height: Float): Sequence<IntRange> = sequence {
        var first = 0
        while (first < layout.lineCount) {
            val top = layout.getLineTop(first)
            var last = first
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= height) last++
            yield(first..last)
            first = last + 1
        }
    }

    /**
     * One picture per page. [pictures] is consumed lazily and each bitmap is recycled once drawn.
     * With [paper] `null` each page takes its picture's size; otherwise the picture is centred inside the margins.
     */
    fun writePictures(pictures: Sequence<Bitmap>, paper: PdfPaper?, output: OutputStream, onPage: (Int) -> Unit = {}): Int {
        val document = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            var count = 0
            for (bitmap in pictures) {
                try {
                    val pageWidth = paper?.width ?: bitmap.width.toFloat()
                    val pageHeight = paper?.height ?: bitmap.height.toFloat()
                    val margin = if (paper == null) 0f else MARGIN / 2
                    val scale = min((pageWidth - 2 * margin) / bitmap.width, (pageHeight - 2 * margin) / bitmap.height)
                    val drawnWidth = bitmap.width * scale
                    val drawnHeight = bitmap.height * scale
                    val left = (pageWidth - drawnWidth) / 2
                    val top = (pageHeight - drawnHeight) / 2
                    val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), ++count).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawnWidth, top + drawnHeight), paint)
                    document.finishPage(page)
                    onPage(count)
                } finally {
                    bitmap.recycle()
                }
            }
            document.writeTo(output)
            return count
        } finally {
            document.close()
        }
    }
}
