package com.xfiles.documents

data class PdfPoint(val x: Float, val y: Float)
data class PdfBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

enum class PdfMarkupKind { Pen, Highlight, Note, Signature }

sealed interface PdfMarkup {
    val page: Int

    data class Ink(override val page: Int, val points: List<PdfPoint>, val signature: Boolean = false) : PdfMarkup
    data class Highlight(override val page: Int, val boxes: List<PdfBox>) : PdfMarkup
    data class Note(override val page: Int, val anchor: PdfPoint, val text: String) : PdfMarkup
}
