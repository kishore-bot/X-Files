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
     * Where a picture of [pictureWidth] x [pictureHeight] pixels sits on its page, in points. The same
     * arithmetic draws the picture and places its recognised text, so the two always line up.
     */
    internal class PictureLayout(
        val pageWidth: Float,
        val pageHeight: Float,
        val left: Float,
        val top: Float,
        val drawnWidth: Float,
        val drawnHeight: Float,
    ) {
        fun pictureRect() = RectF(left, top, left + drawnWidth, top + drawnHeight)
    }

    internal fun layout(pictureWidth: Int, pictureHeight: Int, paper: PdfPaper?): PictureLayout {
        val pageWidth = paper?.width ?: pictureWidth.toFloat()
        val pageHeight = paper?.height ?: pictureHeight.toFloat()
        val margin = if (paper == null) 0f else MARGIN / 2
        val scale = min((pageWidth - 2 * margin) / pictureWidth, (pageHeight - 2 * margin) / pictureHeight)
        val drawnWidth = pictureWidth * scale
        val drawnHeight = pictureHeight * scale
        return PictureLayout(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            left = (pageWidth - drawnWidth) / 2,
            top = (pageHeight - drawnHeight) / 2,
            drawnWidth = drawnWidth,
            drawnHeight = drawnHeight,
        )
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
                    val layout = layout(bitmap.width, bitmap.height, paper)
                    val page = document.startPage(PdfDocument.PageInfo.Builder(layout.pageWidth.toInt(), layout.pageHeight.toInt(), ++count).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bitmap, null, layout.pictureRect(), paint)
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
