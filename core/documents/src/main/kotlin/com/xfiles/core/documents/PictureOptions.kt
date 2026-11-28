package com.xfiles.core.documents

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How pictures are stored in a picture PDF: the longest side kept, and the JPEG
 * quality each page is encoded at. Smaller settings give smaller files.
 */
enum class PdfImageQuality(val label: String, val maxSidePx: Int, val jpegQuality: Int) {
    High("High quality", 2600, 90),
    Balanced("Balanced", 2000, 78),
    Small("Smaller file", 1400, 62),
}

/** The formats a PDF page can be saved as. */
enum class PageImageFormat(val label: String, val extension: String, val mimeType: String, val compressFormat: Bitmap.CompressFormat, val quality: Int) {
    Jpeg("JPEG", "jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 92),
    Png("PNG", "png", "image/png", Bitmap.CompressFormat.PNG, 100),
}

/** Resolutions offered for PDF pages, in dots per inch. */
val PageImageDpiChoices: List<Int> = listOf(72, 150, 300)

const val DEFAULT_PAGE_IMAGE_DPI = 150

/** Most pixels one rendered page may hold: 4 bytes each, so this bounds one bitmap to about 48 MB. */
const val MAX_PAGE_RENDER_PIXELS = 12_000_000L

/** Longest side one rendered page may have, whatever its resolution. */
const val MAX_PAGE_RENDER_SIDE = 8192

/** A page's render size in pixels, and whether it had to be made smaller than the requested DPI. */
data class PageRenderSize(val width: Int, val height: Int, val reduced: Boolean)

/**
 * The pixel size of a [widthPt] x [heightPt] page at [dpi], reduced when it would
 * exceed the pixel or side limits. A reduced page is still sharp for its screen
 * size, and [PageRenderSize.reduced] lets the caller say so.
 */
fun pageRenderSize(widthPt: Float, heightPt: Float, dpi: Int): PageRenderSize {
    val usable = widthPt.isFinite() && heightPt.isFinite() && widthPt > 0f && heightPt > 0f
    val width = (if (usable) widthPt else PdfPaper.Letter.width) * dpi / 72.0
    val height = (if (usable) heightPt else PdfPaper.Letter.height) * dpi / 72.0
    val scale = min(1.0, min(MAX_PAGE_RENDER_SIDE / max(width, height), sqrt(MAX_PAGE_RENDER_PIXELS / (width * height))))
    return PageRenderSize(
        width = floor(width * scale).toInt().coerceAtLeast(1),
        height = floor(height * scale).toInt().coerceAtLeast(1),
        reduced = scale < 1.0,
    )
}
