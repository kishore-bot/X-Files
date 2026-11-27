package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import com.xfiles.design.EzIcons
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.EzSwitch
import com.xfiles.design.EzTextButton
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.book.LineSpacing
import com.xfiles.documents.book.PageMargin
import com.xfiles.documents.book.ReadingFont
import com.xfiles.documents.book.ReadingSettings
import com.xfiles.documents.book.ReadingTheme
import com.xfiles.documents.book.TextAlignment

/**
 * Theme, text size, font, line spacing and margins: the "Aa" sheet, as the EzFiles reader lays it out.
 * Every change applies at once, to the settings this book reads with. [ownSettings] says whether the
 * book has its own, which [onOwnSettings] turns on (a copy of the current settings) or off (back to the
 * global ones). [onReset] puts the settings back to the defaults.
 */
@Composable
internal fun BookSettingsSheet(
    settings: ReadingSettings,
    ownSettings: Boolean,
    mode: BookReadingMode,
    onMode: (BookReadingMode) -> Unit,
    onChange: (ReadingSettings) -> Unit,
    onOwnSettings: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    BookSheetFrame(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BookSectionLabel("Reading mode")
            ChoiceRow(BookReadingMode.entries.map { it to it.label }, mode, onMode)

            BookSectionLabel("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadingTheme.entries.forEach { theme ->
                    ThemeCircle(theme, selected = settings.theme == theme) {
                        haptics.segment()
                        onChange(settings.copy(theme = theme))
                    }
                }
            }

            BookSectionLabel("Text size")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SizeButton(EzIcons.TextDecrease, "Smaller text", settings.textSizeSp > ReadingSettings.MIN_TEXT_SIZE) {
                    onChange(settings.withTextSize(settings.textSizeSp - 1))
                }
                Text(
                    "${settings.textSizeSp}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = EzColors.Text,
                    textAlign = TextAlign.Center,
                )
                SizeButton(EzIcons.TextIncrease, "Larger text", settings.textSizeSp < ReadingSettings.MAX_TEXT_SIZE) {
                    onChange(settings.withTextSize(settings.textSizeSp + 1))
                }
            }

            BookSectionLabel("Font")
            ChoiceRow(ReadingFont.entries.map { it to it.label() }, settings.font) { onChange(settings.copy(font = it)) }

            BookSectionLabel("Line spacing")
            ChoiceRow(LineSpacing.entries.map { it to it.label() }, settings.lineSpacing) {
                onChange(settings.copy(lineSpacing = it))
            }

            BookSectionLabel("Margins")
            ChoiceRow(PageMargin.entries.map { it to it.label() }, settings.margin) { onChange(settings.copy(margin = it)) }

            BookSectionLabel("Alignment")
            ChoiceRow(
                listOf(TextAlignment.Original to "Book's own", TextAlignment.Justified to "Justified"),
                settings.alignment,
            ) { onChange(settings.copy(alignment = it)) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use for this book only", style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
                    Text(
                        if (ownSettings) "This book keeps its own settings." else "This book follows the global settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                    )
                }
                EzSwitch(checked = ownSettings, onCheckedChange = onOwnSettings)
            }
            EzTextButton("Reset to default", onReset, modifier = Modifier.align(Alignment.End))
        }
    }
}

/** A page theme: a round swatch with "Aa" in the page's own colours, and its name under it. */
@Composable
private fun ThemeCircle(theme: ReadingTheme, selected: Boolean, onClick: () -> Unit) {
    val paint = bookPalette(theme)
    val name = theme.themeName()
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !selected, role = Role.RadioButton, onClickLabel = name, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(paint.background)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) EzColors.Primary else EzColors.FieldOutline,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = paint.text, fontSize = 18.sp, fontFamily = FontFamily.Serif)
        }
        Text(name, style = MaterialTheme.typography.labelSmall, color = EzColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A text-size action with a disabled state at the supported limit. */
@Composable
private fun SizeButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(1.dp, EzColors.FieldOutline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description) {
                haptics.segment()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) EzColors.Text else EzColors.TextSecondary.copy(alpha = 0.4f),
        )
    }
}

private fun ReadingTheme.themeName(): String = when (this) {
    ReadingTheme.Light -> "White"
    ReadingTheme.Sepia -> "Sepia"
    ReadingTheme.Green -> "Green"
    ReadingTheme.Dark -> "Dark"
    ReadingTheme.Black -> "Black"
}

private fun ReadingFont.label(): String = when (this) {
    ReadingFont.Original -> "Book's own"
    ReadingFont.Serif -> "Serif"
    ReadingFont.Caladea -> "Caladea"
    ReadingFont.Sans -> "Sans"
    ReadingFont.Mono -> "Mono"
}

private fun LineSpacing.label(): String = when (this) {
    LineSpacing.Original -> "Book's own"
    LineSpacing.Compact -> "Tight"
    LineSpacing.Normal -> "Normal"
    LineSpacing.Loose -> "Loose"
}

private fun PageMargin.label(): String = when (this) {
    PageMargin.Narrow -> "Narrow"
    PageMargin.Medium -> "Normal"
    PageMargin.Wide -> "Wide"
}
