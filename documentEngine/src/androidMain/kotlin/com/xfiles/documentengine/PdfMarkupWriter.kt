package com.xfiles.documentengine

import com.xfiles.documents.PdfMarkup
import com.xfiles.engine.ffi.NativePdfAnnotation
import com.xfiles.engine.ffi.NativePdfBox
import com.xfiles.engine.ffi.NativePdfInkStyle
import com.xfiles.engine.ffi.NativePdfStroke
import com.xfiles.engine.ffi.pdfAppendPath
import com.xfiles.engine.ffi.pdfInspectPath
import java.io.File

fun writePdfMarkup(source: File, output: File, marks: List<PdfMarkup>): Int {
    val info = pdfInspectPath(source.absolutePath)
    require(!info.encrypted) { "An encrypted PDF cannot be annotated." }
    require(!info.isSigned) { "This PDF has a digital signature and cannot be annotated." }
    require(!info.archival) { "This PDF/A document cannot be annotated." }
    val annotations = marks.map { mark ->
        val page = mark.page.toUInt()
        when (mark) {
            is PdfMarkup.Highlight -> NativePdfAnnotation.Highlight(
                page,
                0x75FFC928u,
                mark.boxes.map { NativePdfBox(it.left, it.top, it.right, it.bottom) },
            )
            is PdfMarkup.Ink -> NativePdfAnnotation.Ink(
                page,
                if (mark.signature) 0xFF273477u else 0xFFB90020u,
                if (mark.signature) 1.6f else 2.4f,
                listOf(NativePdfStroke(mark.points.flatMap { point -> listOf(point.x, point.y) })),
                if (mark.signature) NativePdfInkStyle.SIGNATURE else NativePdfInkStyle.PEN,
            )
            is PdfMarkup.Note -> NativePdfAnnotation.Note(page, 0xFFFFC928u, mark.anchor.x, mark.anchor.y, mark.text)
        }
    }
    pdfAppendPath(source.absolutePath, output.absolutePath, annotations)
    return info.pageCount.toInt()
}
