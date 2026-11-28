package com.xfiles.core.documents

import kotlin.math.roundToLong

/** Receives a PDF as it is written. */
internal fun interface PdfSink {
    fun write(bytes: ByteArray, length: Int)
}

/**
 * Writes a PDF whose pages each hold one JPEG, as it goes. The platform's PdfDocument
 * keeps every page's pixels until the end and stores them losslessly, so memory grows
 * with the page count and a quality setting could only change resolution. Here each
 * JPEG is passed through untouched (DCTDecode) and then forgotten. Call [finish] once.
 */
internal class JpegPdfWriter(private val sink: PdfSink) {
    private var position = 0L

    /** Index is the object number. 0 is unused and 2, the page tree, is written last. */
    private val offsets = mutableListOf(0L, 0L, 0L)
    private val pageObjects = mutableListOf<Int>()

    init {
        // The version, then a comment of non-ASCII bytes that marks the file as binary.
        emit(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A, 0x25, -30, -29, -49, -45, 0x0A))
        offsets[1] = position
        emit("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    }

    /**
     * Adds a page [pageWidth] x [pageHeight] points with the picture drawn at
     * ([left], [top]) with size [width] x [height], measured from the top left.
     * [jpeg] must be RGB, which is what the platform's encoders produce.
     */
    fun addPage(
        pageWidth: Float,
        pageHeight: Float,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        pixelWidth: Int,
        pixelHeight: Int,
        jpeg: ByteArray,
    ) {
        val page = offsets.size
        val contents = page + 1
        val image = page + 2
        pageObjects += page
        offsets += position
        emit(
            "$page 0 obj\n<< /Type /Page /Parent 2 0 R " +
                "/MediaBox [0 0 ${pdfNumber(pageWidth)} ${pdfNumber(pageHeight)}] " +
                "/Resources << /XObject << /Im0 $image 0 R >> >> /Contents $contents 0 R >>\nendobj\n",
        )
        // PDF's y axis runs upwards from the bottom of the page.
        val bottom = pageHeight - top - height
        val drawing = "q ${pdfNumber(width)} 0 0 ${pdfNumber(height)} ${pdfNumber(left)} ${pdfNumber(bottom)} cm /Im0 Do Q"
        offsets += position
        emit("$contents 0 obj\n<< /Length ${drawing.length} >>\nstream\n$drawing\nendstream\nendobj\n")
        offsets += position
        emit(
            "$image 0 obj\n<< /Type /XObject /Subtype /Image /Width $pixelWidth /Height $pixelHeight " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n",
        )
        emit(jpeg)
        emit("\nendstream\nendobj\n")
    }

    fun finish() {
        offsets[2] = position
        val kids = pageObjects.joinToString(" ") { "$it 0 R" }
        emit("2 0 obj\n<< /Type /Pages /Count ${pageObjects.size} /Kids [$kids] >>\nendobj\n")
        val xref = position
        val table = StringBuilder("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for (index in 1 until offsets.size) {
            table.append(offsets[index].toString().padStart(10, '0')).append(" 00000 n \n")
        }
        table.append("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        emit(table.toString())
    }

    private fun emit(text: String) = emit(text.encodeToByteArray())

    private fun emit(bytes: ByteArray) {
        sink.write(bytes, bytes.size)
        position += bytes.size
    }
}

/** Two decimals and never an exponent, which PDF numbers don't allow. */
internal fun pdfNumber(value: Float): String {
    val hundredths = (value * 100).roundToLong()
    val whole = hundredths / 100
    val fraction = (if (hundredths < 0) -hundredths else hundredths) % 100
    val sign = if (hundredths < 0 && whole == 0L) "-" else ""
    return "$sign$whole.${fraction.toString().padStart(2, '0')}"
}
