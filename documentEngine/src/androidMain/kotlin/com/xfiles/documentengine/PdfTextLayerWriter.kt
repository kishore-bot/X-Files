package com.xfiles.documentengine

import com.xfiles.engine.ffi.NativeTextLine
import com.xfiles.engine.ffi.pdfAddTextLayerPath
import java.io.File

/**
 * One OCR line placed on a page. Geometry is a fraction (0..1) of the displayed page,
 * origin top-left; [page] is zero-based.
 */
data class PdfTextLine(
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
)

/**
 * Writes [source] plus an invisible, searchable text layer built from [lines] into [output].
 * Rust appends one incremental update and re-reads it before returning; on failure nothing
 * is left at [output]. [lines] must be non-empty; the caller skips the layer when OCR found nothing.
 */
fun writePdfTextLayer(source: File, output: File, lines: List<PdfTextLine>) {
    require(lines.isNotEmpty()) { "No text was found to add." }
    pdfAddTextLayerPath(
        source.absolutePath,
        output.absolutePath,
        lines.map { NativeTextLine(it.page.toUInt(), it.left, it.top, it.right, it.bottom, it.text) },
    )
}
