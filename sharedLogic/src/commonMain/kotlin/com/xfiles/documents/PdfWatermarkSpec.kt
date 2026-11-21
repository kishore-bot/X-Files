package com.xfiles.documents

/** Where a watermark sits on each page. */
enum class PdfWatermarkPosition(val label: String) {
    Center("Centre"),
    TopLeft("Top left"),
    TopCenter("Top"),
    TopRight("Top right"),
    BottomLeft("Bottom left"),
    BottomCenter("Bottom"),
    BottomRight("Bottom right"),
    Tiled("Tiled"),
}

/**
 * A text watermark. [color] is 0xRRGGBB, [angleDegrees] runs counter-clockwise,
 * and [pages] holds zero-based pages (null means every page).
 */
data class PdfWatermarkSpec(
    val text: String,
    val fontSize: Float = 48f,
    val opacity: Float = 0.3f,
    val angleDegrees: Float = 45f,
    val color: Int = 0x808080,
    val position: PdfWatermarkPosition = PdfWatermarkPosition.Center,
    val pages: List<Int>? = null,
) {
    /** Why this spec can't be applied, or null. Shown live as the user types. */
    fun validationError(): String? = when {
        text.isBlank() -> "Enter the text for the watermark."
        text.trim().length > MAX_WATERMARK_CHARS -> "Keep the text to $MAX_WATERMARK_CHARS characters."
        fontSize !in 6f..300f -> "The size has to be between 6 and 300 points."
        opacity !in 0f..1f -> "Opacity has to be between 0 and 100 percent."
        !angleDegrees.isFinite() -> "That angle isn't valid."
        else -> null
    }

    companion object {
        const val MAX_WATERMARK_CHARS = 200
    }
}
