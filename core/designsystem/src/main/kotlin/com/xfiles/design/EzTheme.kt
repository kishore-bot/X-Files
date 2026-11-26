package com.xfiles.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every colour of the app's chrome, for one theme. Screens read them through
 * [EzColors]; the surfaces in Surfaces.kt read the glass and wave entries.
 */
@Immutable
class EzPalette(
    val dark: Boolean,
    val background: Color,
    val backgroundSecondary: Color,
    val text: Color,
    val textSecondary: Color,
    /** Text actions and warnings: AA contrast on this theme's glass. */
    val accent: Color,
    /** The glass fill, top to bottom, at full strength; [glassAlpha] scales it. */
    val glassTop: Color,
    val glassBottom: Color,
    /** The alpha of the fill at strength 0 and 1. */
    val glassAlphaMin: Float,
    val glassAlphaMax: Float,
    /** The thin edge: bright along the top, fading towards the bottom. */
    val glassEdgeTop: Color,
    val glassEdgeBottom: Color,
    /** The inner glow under the top edge. */
    val glassGlow: Color,
    /** The soft shadow under bars, panels and rows. */
    val glassShadow: Color,
    val dialogSurface: Color,
    val sheetSurface: Color,
    val hairline: Color,
    val selected: Color,
    val fieldOutline: Color,
    val pageOutline: Color,
    val sliderTrack: Color,
    /** The flowing waves behind the library. */
    val waveBase: List<Color>,
    val waveGlowTop: Color,
    val waveGlowBottom: Color,
    val ribbonTop: List<Color>,
    val ribbonBottom: List<Color>,
    /** The calmer waves behind a document. */
    val readerBase: List<Color>,
    val readerGlow: Color,
)

/** The fill's alpha for a glass [strength] 0..1 (subtle to nearly opaque). */
internal fun EzPalette.glassAlpha(strength: Float): Float =
    glassAlphaMin + (glassAlphaMax - glassAlphaMin) * strength.coerceIn(0f, 1f)

val LightPalette = EzPalette(
    dark = false,
    background = Color(0xFFFFF4F8),
    backgroundSecondary = Color(0xFFF8E8F0),
    text = Color(0xFF17121A),
    textSecondary = Color(0xFF625B66),
    accent = Color(0xFFB90020),
    glassTop = Color(0xFFFFFFFF),
    glassBottom = Color(0xFFFFEFF5),
    glassAlphaMin = 0.12f,
    glassAlphaMax = 1f,
    glassEdgeTop = Color.White.copy(alpha = 0.95f),
    glassEdgeBottom = Color.White.copy(alpha = 0.4f),
    glassGlow = Color.White.copy(alpha = 0.5f),
    glassShadow = Color(0xFFB4284F),
    dialogSurface = Color(0xFFFFFBFD),
    sheetSurface = Color(0xFFFFF7FA),
    hairline = Color(0xFFEBDDE5),
    selected = Color(0xFFFFD9DE),
    fieldOutline = Color(0xFF8F7F89),
    pageOutline = Color(0xFFDED6E0),
    sliderTrack = Color(0xFFE2D5DD),
    waveBase = listOf(Color(0xFFFFF8FB), Color(0xFFF9E6EF), Color(0xFFF5DCE9)),
    waveGlowTop = Color(0x52FF305B),
    waveGlowBottom = Color(0x6BFFA7C8),
    ribbonTop = listOf(Color(0x80FF6A86), Color(0x33FFB3C6)),
    ribbonBottom = listOf(Color(0x40FF8FAA), Color(0x66E60023)),
    readerBase = listOf(Color(0xFFEEE3EF), Color(0xFFF4E9F3)),
    readerGlow = Color(0x5CF7A4CA),
)

val DarkPalette = EzPalette(
    dark = true,
    background = Color(0xFF140A12),
    backgroundSecondary = Color(0xFF22121D),
    text = Color(0xFFF7ECF2),
    textSecondary = Color(0xFFC6B5C0),
    accent = Color(0xFFFF7088),
    glassTop = Color(0xFF4A2A3D),
    glassBottom = Color(0xFF261420),
    glassAlphaMin = 0.4f,
    glassAlphaMax = 0.98f,
    glassEdgeTop = Color.White.copy(alpha = 0.26f),
    glassEdgeBottom = Color.White.copy(alpha = 0.06f),
    glassGlow = Color.White.copy(alpha = 0.09f),
    glassShadow = Color(0xFF000000),
    dialogSurface = Color(0xFF2A1823),
    sheetSurface = Color(0xFF24141E),
    hairline = Color(0xFF3F2A36),
    selected = Color(0xFF5C1A2E),
    fieldOutline = Color(0xFF9C8794),
    pageOutline = Color(0xFF3D2A36),
    sliderTrack = Color(0xFF4A3542),
    waveBase = listOf(Color(0xFF1C0D18), Color(0xFF150A13), Color(0xFF0F070D)),
    waveGlowTop = Color(0x47E60023),
    waveGlowBottom = Color(0x38A3144A),
    ribbonTop = listOf(Color(0x70B3123A), Color(0x1A7A1030)),
    ribbonBottom = listOf(Color(0x2E8A1838), Color(0x5CB0102E)),
    readerBase = listOf(Color(0xFF120910), Color(0xFF1A0E17)),
    readerGlow = Color(0x33A0204A),
)

val LocalEzPalette = staticCompositionLocalOf { LightPalette }

/**
 * Design tokens of the Xfiles design system (Material 3 Expressive + glass).
 * The brand colours are the same in both themes; the rest follow the theme,
 * so they're read inside composition. Content drawn on a document's white
 * page uses [EzPaper] instead, which never changes.
 */
object EzColors {
    val Primary = Color(0xFFE60023)
    val PrimaryLight = Color(0xFFFF4564)
    val PrimaryDark = Color(0xFFB90020)

    /** Text and icons on the red gradient. */
    val OnPrimary = Color.White

    val Positive = Color(0xFF1E9E52)

    /** Controls drawn over a document or a photo (full-screen exit, slide counter): the same in both themes. */
    val Scrim = Color.Black.copy(alpha = 0.55f)
    val OnScrim = Color.White

    val Background: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.background
    val BackgroundSecondary: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.backgroundSecondary

    val Text: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.text
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.textSecondary

    /**
     * Text actions and warnings on glass. The brand red is just under 4.5:1 on
     * the pink backgrounds, so light uses a darker red and dark a lighter one.
     */
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.accent

    /** Dialogs, sheets and menus: near-opaque so text over the dimmed screen stays readable. */
    val DialogSurface: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.dialogSurface
    val SheetSurface: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.sheetSurface

    /** Thin separators and the outlines of unfocused fields (3:1 against the surface). */
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.hairline

    /** Behind the selected tool, mode or tab. */
    val Selected: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.selected
    val FieldOutline: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.fieldOutline

    /** Around page thumbnails and cards on the background. */
    val PageOutline: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.pageOutline

    /** The unfilled part of a slider or progress track. */
    val SliderTrack: Color @Composable @ReadOnlyComposable get() = LocalEzPalette.current.sliderTrack

    /** Behind matched text in block documents, and behind the current match (drawn on the page). */
    val SearchMatch = EzPaper.SearchMatch
    val SearchCurrent = EzPaper.SearchCurrent

    /** Translucent, drawn over a PDF page image: every match, and the current one. */
    val PdfSearchMatch = Color(0x59FFD400)
    val PdfSearchCurrent = Color(0x99FFC400)
    val PdfSearchCurrentOutline = Color(0xFFE09600)
}

/**
 * Colours for what's drawn on a document's page, which stays white in both
 * themes (the file's own colours win wherever it has them).
 */
object EzPaper {
    val Background = Color.White
    val Text = Color(0xFF17121A)
    val TextSecondary = Color(0xFF625B66)
    val Outline = Color(0xFFDED6E0)
    val SearchMatch = Color(0xFFFFE27A)
    val SearchCurrent = Color(0xFFFF9B3D)
}

@Immutable
data class PaperColors(
    val background: Color,
    val text: Color,
    val textSecondary: Color,
    val outline: Color,
    val quoteBackground: Color,
    val inlineCode: Color,
    val tableHeader: Color,
    val tableStripe: Color,
    val tableBorder: Color,
)

val LightPaperColors = PaperColors(
    background = EzPaper.Background,
    text = EzPaper.Text,
    textSecondary = EzPaper.TextSecondary,
    outline = EzPaper.Outline,
    quoteBackground = Color(0xFFFDF1F5),
    inlineCode = Color(0xFFF6E8EF),
    tableHeader = Color(0xFFFBE7EE),
    tableStripe = Color(0xFFFCF8FA),
    tableBorder = Color(0xFFEBDDE4),
)

val DarkPaperColors = PaperColors(
    background = Color(0xFF1E1620),
    text = Color(0xFFF7ECF2),
    textSecondary = Color(0xFFC6B5C0),
    outline = Color(0xFF3F2A36),
    quoteBackground = Color(0xFF2D1F28),
    inlineCode = Color(0xFF2A1E26),
    tableHeader = Color(0xFF2E1F28),
    tableStripe = Color(0xFF231820),
    tableBorder = Color(0xFF3F2A36),
)

val LocalPaperColors = staticCompositionLocalOf { LightPaperColors }

/** The coloured badges of file types in the library: the same in both themes, white text on top. */
object EzBadgeColors {
    /** The label and gloss on a badge. */
    val Ink = Color.White
    val Red = Color(0xFFFF5969) to Color(0xFFD90020)
    val Blue = Color(0xFF54B2FF) to Color(0xFF0862D9)
    val Green = Color(0xFF46C987) to Color(0xFF087F42)
    val Orange = Color(0xFFFFA05A) to Color(0xFFE0570B)
    val Purple = Color(0xFFC06BF0) to Color(0xFF8A2BC2)
    val Violet = Color(0xFF8E7CFF) to Color(0xFF5B3FD9)
    val Teal = Color(0xFF3CC4C9) to Color(0xFF0B7F86)
    val Grey = Color(0xFFA4A8B3) to Color(0xFF5C6270)
    val Slate = Color(0xFF6B7280) to Color(0xFF373C47)
    val Indigo = Color(0xFF5A6CF0) to Color(0xFF2F3BB5)
    val Steel = Color(0xFF8E96A8) to Color(0xFF4C5568)
    val Plum = Color(0xFF8B5CF6) to Color(0xFF5B21B6)
    val Pink = Color(0xFFF472B6) to Color(0xFFBE185D)

    /**
     * A type's colour as an icon or text on glass: the lighter stop on the dark
     * theme, the deeper one on the light, so it keeps contrast in both.
     */
    @Composable
    @ReadOnlyComposable
    fun ink(pair: Pair<Color, Color>): Color = if (LocalEzPalette.current.dark) pair.first else pair.second
}

object EzShapes {
    val Small = RoundedCornerShape(14.dp)
    val Medium = RoundedCornerShape(22.dp)
    val Large = RoundedCornerShape(30.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Card = RoundedCornerShape(20.dp)
    val Page = RoundedCornerShape(9.dp)

    /** Top and bottom bars. */
    val Bar = Large
    val Dialog = RoundedCornerShape(28.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Drawer = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)
    val Menu = RoundedCornerShape(18.dp)
    val Field = RoundedCornerShape(14.dp)
}

/** Spacing and sizes shared by every screen's chrome. */
object EzSpacing {
    /** Space between the screen edge and bars, cards and lists. */
    val Gutter = 16.dp

    /** The reader and editor keep a narrower gutter so the page gets the width. */
    val ReaderGutter = 12.dp

    /** Inside cards, dialogs and sheets. */
    val Panel = 20.dp
    val ItemGap = 12.dp

    /** Icons in bars and on buttons. */
    val BarIcon = 24.dp

    /** The minimum touch target (Compose also widens smaller targets to this). */
    val Touch = 48.dp
}

private val Typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** File names in library rows and cards. */
val FileNameStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)

private fun EzPalette.colorScheme(): ColorScheme {
    val base = if (dark) {
        darkColorScheme(
            primaryContainer = selected,
            onPrimaryContainer = Color(0xFFFFD9DE),
            secondaryContainer = Color(0xFF3D2432),
        )
    } else {
        lightColorScheme(
            primaryContainer = selected,
            onPrimaryContainer = EzColors.PrimaryDark,
            secondaryContainer = Color(0xFFF7DCE7),
        )
    }
    return base.copy(
        primary = EzColors.Primary,
        onPrimary = EzColors.OnPrimary,
        onSecondaryContainer = text,
        background = background,
        onBackground = text,
        surface = dialogSurface,
        onSurface = text,
        surfaceVariant = backgroundSecondary,
        onSurfaceVariant = textSecondary,
        outline = fieldOutline,
        outlineVariant = hairline,
        error = accent,
        // Material containers that aren't wrapped (menus, pickers) still match the glass panels.
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = if (dark) background else Color.White,
        surfaceContainerLow = sheetSurface,
        surfaceContainer = dialogSurface,
        surfaceContainerHigh = dialogSurface,
        surfaceContainerHighest = backgroundSecondary,
    )
}

/** The effective app theme, including an explicit override of the system theme. */
val LocalEzDarkTheme = staticCompositionLocalOf { false }

/** Switch related foreground and surface colors together without recreating the activity. */
@Composable
fun EzTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalEzPalette provides palette, LocalEzDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = remember(palette) { palette.colorScheme() },
            // Text fields and menus take extraSmall; dialogs and sheets extraLarge.
            shapes = Shapes(extraSmall = EzShapes.Field, extraLarge = EzShapes.Dialog),
            typography = Typography,
        ) {
            // Text without its own colour reads on the theme's glass.
            CompositionLocalProvider(LocalContentColor provides palette.text, content = content)
        }
    }
}
