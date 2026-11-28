package com.xfiles.documents

/** One pen stroke, points as fractions (0..1) of the signature box, origin top-left. */
typealias PdfSignStroke = List<Pair<Float, Float>>

/** Where a signature sits: a box in fractions of the displayed page, origin top-left. */
data class PdfSignPlacement(
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * A drawn signature placed on a page. This is a visual mark only: no key is
 * involved and nothing in the file claims a cryptographic signature.
 */
data class PdfSignSpec(
    val placement: PdfSignPlacement,
    val strokes: List<PdfSignStroke>,
    /** 0xRRGGBB. */
    val color: Int = 0x1A237E,
    /** Points, 0.5 to 6. */
    val lineWidth: Float = 1.6f,
) {
    fun validationError(): String? = when {
        strokes.isEmpty() || strokes.all { it.isEmpty() } -> "Draw a signature first."
        placement.width <= 0f || placement.height <= 0f -> "Make the signature larger."
        placement.x < 0f || placement.y < 0f || placement.x + placement.width > 1.0001f ||
            placement.y + placement.height > 1.0001f -> "Keep the signature on the page."
        else -> null
    }
}

/** A signature kept on this device, normalised to its own box. */
data class PdfSavedSignature(val strokes: List<PdfSignStroke>)

const val MAX_SAVED_SIGNATURES = 5

private const val MAX_STORED_POINTS = 20_000

/**
 * Compact text form: signatures on lines, strokes separated by `|`, points by
 * `;`, coordinates by `,`. Values keep four decimals, which is well below a pixel.
 */
fun encodePdfSavedSignatures(signatures: List<PdfSavedSignature>): String =
    signatures.take(MAX_SAVED_SIGNATURES).joinToString("\n") { signature ->
        signature.strokes.joinToString("|") { stroke ->
            stroke.joinToString(";") { (x, y) -> "${fixed4(x)},${fixed4(y)}" }
        }
    }

/**
 * Reads what [encodePdfSavedSignatures] wrote. Malformed lines are skipped rather
 * than failing the whole list, and the count and size are capped.
 */
fun decodePdfSavedSignatures(text: String): List<PdfSavedSignature> {
    var points = 0
    val result = ArrayList<PdfSavedSignature>()
    for (line in text.lineSequence()) {
        if (result.size >= MAX_SAVED_SIGNATURES) break
        if (line.isBlank()) continue
        val strokes = ArrayList<PdfSignStroke>()
        var valid = true
        for (strokeText in line.split('|')) {
            val stroke = ArrayList<Pair<Float, Float>>()
            for (pointText in strokeText.split(';')) {
                val parts = pointText.split(',')
                val x = parts.getOrNull(0)?.toFloatOrNull()
                val y = parts.getOrNull(1)?.toFloatOrNull()
                if (x == null || y == null || parts.size != 2 || x !in 0f..1f || y !in 0f..1f) {
                    valid = false
                    break
                }
                stroke += x to y
            }
            points += stroke.size
            if (!valid || points > MAX_STORED_POINTS) {
                valid = false
                break
            }
            if (stroke.isNotEmpty()) strokes += stroke
        }
        if (valid && strokes.isNotEmpty()) result += PdfSavedSignature(strokes)
    }
    return result
}

private fun fixed4(value: Float): String {
    val scaled = (value.coerceIn(0f, 1f) * 10_000f + 0.5f).toInt()
    val whole = scaled / 10_000
    val fraction = (scaled % 10_000).toString().padStart(4, '0')
    return "$whole.$fraction"
}
