package com.xfiles.core.documents

/** A visible rectangle in the displayed page's pixel coordinates. */
data class PdfRasterRegion(
    val pageWidth: Int,
    val pageHeight: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
