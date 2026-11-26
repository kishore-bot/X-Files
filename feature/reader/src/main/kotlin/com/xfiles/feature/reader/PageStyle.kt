package com.xfiles.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The page style of one file, chosen per file type and used by every viewer. [Original] uses white paper with the author's
 * formatting; [Standard] follows the app's light or dark theme; [Dark] and [Sepia] are fixed
 * pages whose text always contrasts with them, whatever the app theme.
 */
enum class PageStyle(val label: String) {
    Original("Original"),
    Standard("Standard"),
    Dark("Dark"),
    Sepia("Sepia");

    /** The filter for rendered pages (PDF): dark inverts with the hues kept, sepia tones the paper. Null otherwise. */
    fun pageFilter(): ColorFilter? = when (this) {
        Dark -> ColorFilter.colorMatrix(ColorMatrix(DARK_PAGE))
        Sepia -> ColorFilter.colorMatrix(ColorMatrix(SEPIA_PAGE))
        else -> null
    }

    companion object {
        /** Invert, then rotate the hues by 180 degrees: the usual dark-reading filter. */
        private val DARK_PAGE: FloatArray = multiply(hueRotate180(), invert())

        /** The sepia tone matrix (W3C feColorMatrix). */
        private val SEPIA_PAGE = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

        private fun invert() = floatArrayOf(
            -1f, 0f, 0f, 0f, 1f,
            0f, -1f, 0f, 0f, 1f,
            0f, 0f, -1f, 0f, 1f,
            0f, 0f, 0f, 1f, 0f,
        )

        /** Hue rotation by 180 degrees (cos -1, sin 0) in the W3C coefficients. */
        private fun hueRotate180() = floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

        /** Row-major 4x5 colour matrices: [outer] applied after [inner]. */
        private fun multiply(outer: FloatArray, inner: FloatArray): FloatArray {
            val result = FloatArray(20)
            for (row in 0 until 4) {
                for (col in 0 until 5) {
                    var sum = if (col == 4) outer[row * 5 + 4] else 0f
                    for (k in 0 until 4) sum += outer[row * 5 + k] * inner[k * 5 + col]
                    result[row * 5 + col] = sum
                }
            }
            return result
        }
    }
}

/** The page style of the document on screen; provided by the reader body. */
val LocalPageStyle = compositionLocalOf { PageStyle.Standard }

@Composable
internal fun Modifier.pageCard(): Modifier =
    clip(RoundedCornerShape(12.dp)).background(LocalReaderColors.current.header)

@Composable
internal fun Modifier.pagePanel(corner: androidx.compose.ui.unit.Dp = 16.dp): Modifier =
    clip(RoundedCornerShape(corner)).background(LocalReaderColors.current.page)
