package com.xfiles.core.documents

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.min

/**
 * Lays text and pictures out as PDF files. Text goes through the platform's [PdfDocument], which embeds
 * fonts; pictures go through [JpegPdfWriter], so each page keeps its JPEG and nothing else. Pages are
 * produced lazily from [Sequence]s, so only one page's bitmap is alive at a time. Not thread-safe:
 * callers run it on one lane.
 */
internal object PdfComposer {
    private const val MARGIN = 27f // Half an inch, around pictures placed on a paper size.

    /** Every page of [layout] as a PDF; returns the page count. [onPage] reports each finished page. */
    fun writeText(layout: TextPdfLayout, output: OutputStream, onPage: (Int) -> Unit = {}): Int {
        val document = PdfDocument()
        try {
            val box = layout.box
            for (index in layout.pages.indices) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(box.pageWidth, box.pageHeight, index + 1).create())
                layout.draw(page.canvas, index)
                document.finishPage(page)
                onPage(index + 1)
            }
            document.writeTo(output)
            return layout.pages.size
        } finally {
            document.close()
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
    )

    internal fun layout(pictureWidth: Int, pictureHeight: Int, paper: PdfPaper?): PictureLayout {
        val pageWidth = paper?.width ?: pictureWidth.toFloat()
        val pageHeight = paper?.height ?: pictureHeight.toFloat()
        val margin = if (paper == null) 0f else MARGIN
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
     * One picture per page, each encoded as a JPEG at [quality] and then released. [pictures] is consumed
     * lazily. With [paper] `null` each page takes its picture's size; otherwise the picture is centred.
     */
    fun writePictures(
        pictures: Sequence<Bitmap>,
        paper: PdfPaper?,
        quality: PdfImageQuality,
        output: OutputStream,
        onPage: (Int) -> Unit = {},
    ): Int {
        val writer = JpegPdfWriter { bytes, length -> output.write(bytes, 0, length) }
        var count = 0
        for (bitmap in pictures) {
            try {
                val layout = layout(bitmap.width, bitmap.height, paper)
                val flat = opaque(bitmap)
                try {
                    val jpeg = ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, it) }.toByteArray()
                    writer.addPage(
                        pageWidth = layout.pageWidth,
                        pageHeight = layout.pageHeight,
                        left = layout.left,
                        top = layout.top,
                        width = layout.drawnWidth,
                        height = layout.drawnHeight,
                        pixelWidth = flat.width,
                        pixelHeight = flat.height,
                        jpeg = jpeg,
                    )
                } finally {
                    if (flat !== bitmap) flat.recycle()
                }
                onPage(++count)
            } finally {
                bitmap.recycle()
            }
        }
        writer.finish()
        return count
    }

    /** JPEG has no transparency; transparent pixels would turn black, so they go onto white. */
    fun opaque(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }
}
