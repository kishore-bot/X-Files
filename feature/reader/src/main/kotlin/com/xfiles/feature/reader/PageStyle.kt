package com.xfiles.feature.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.xfiles.design.glassCard
import com.xfiles.design.glassPanel

/**
 * The page style of one file, chosen per file type and used by every viewer. [Original] keeps the author's
 * colours on a white page; [Standard] follows the app's light or dark theme; [Dark] and [Sepia] are fixed
 * pages whose text always contrasts with them, whatever the app theme.
 */
enum class PageStyle(val label: String) {
    Original("Original"),
    Standard("Standard"),
    Dark("Dark"),
    Sepia("Sepia");

    /** Page and text colours for the viewers that draw their own page, or null for [Standard] (the app's colours). */
    fun colors(): PageColors? = when (this) {
        Standard -> null
        Original -> PageColors(page = Color.White, text = Color(0xFF161616), secondary = Color(0xFF5A5A5A), syntaxDark = false)
        Dark -> PageColors(page = Color(0xFF121214), text = Color(0xFFE4E2DE), secondary = Color(0xFF9B9A96), syntaxDark = true)
        Sepia -> PageColors(page = Color(0xFFF4ECD8), text = Color(0xFF3B2F22), secondary = Color(0xFF7A6A55), syntaxDark = false)
    }

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

/** Colours of a page that a viewer draws itself. */
@Immutable
data class PageColors(
    val page: Color,
    val text: Color,
    val secondary: Color,
    /** Whether syntax colours should use the dark-page set. */
    val syntaxDark: Boolean,
)

/** The page style of the document on screen; provided by the reader body. */
val LocalPageStyle = compositionLocalOf { PageStyle.Standard }

/** The viewer's page surface: the page colour for a fixed style, else [default] (the app's surface). */
@Composable
internal fun pageSurface(default: Color): Color = LocalPageStyle.current.colors()?.page ?: default

/** A cell or header band: a faint tint of the page's text colour, else [default]. */
@Composable
internal fun pageVariant(default: Color): Color =
    LocalPageStyle.current.colors()?.let { it.secondary.copy(alpha = 0.14f) } ?: default

/** Secondary text on the page, else [default]. */
@Composable
internal fun pageSecondary(default: Color): Color = LocalPageStyle.current.colors()?.secondary ?: default

/** Primary text on the page, else [default]. */
@Composable
internal fun pageText(default: Color): Color = LocalPageStyle.current.colors()?.text ?: default

/** A card on the page: the app's glass for [PageStyle.Standard], a faint tint for the fixed pages. */
@Composable
internal fun Modifier.pageCard(): Modifier {
    val colors = LocalPageStyle.current.colors() ?: return this.glassCard()
    return this.clip(RoundedCornerShape(12.dp)).background(colors.secondary.copy(alpha = 0.10f))
}

/** The page itself, filling its parent: glass for [PageStyle.Standard], the page colour for the fixed styles. */
@Composable
internal fun Modifier.pagePanel(corner: androidx.compose.ui.unit.Dp = 16.dp): Modifier {
    val colors = LocalPageStyle.current.colors()
    val shape = RoundedCornerShape(corner)
    return if (colors == null) this.glassPanel(shape, elevation = 3.dp) else this.clip(shape).background(colors.page)
}
