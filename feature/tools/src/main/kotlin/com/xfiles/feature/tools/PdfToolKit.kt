package com.xfiles.feature.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.EzTextButton
import com.xfiles.design.PrimaryActionButton
import com.xfiles.design.glassCard
import com.xfiles.design.glossyPrimary
import com.xfiles.design.pressScale
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.PdfProtectionChoice
import kotlinx.coroutines.CancellationException

/** Runs [block], or returns null when it fails. Cancellation is never swallowed. */
internal suspend fun <T> loadOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    null
}

/**
 * A page or picture preview. It loads off the main thread under [key]; until it lands, a quiet document glyph
 * holds the frame. [rotationDegrees] turns the preview, for pages turned in Reorder.
 */
@Composable
internal fun PreviewTile(
    key: Any,
    load: suspend () -> Bitmap,
    modifier: Modifier = Modifier,
    description: String? = null,
    rotationDegrees: Float = 0f,
) {
    val image by produceState<ImageBitmap?>(null, key) { value = loadOrNull(load)?.asImageBitmap() }
    Box(modifier.clip(EzShapes.Page).background(EzColors.Selected), contentAlignment = Alignment.Center) {
        val loaded = image
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = description,
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = rotationDegrees },
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(EzIcons.Document, contentDescription = null, tint = EzColors.TextSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The source step of a single-PDF tool. Until a PDF is open it is a dashed drop-zone; then the first page as a card
 * with a change control. A protected PDF asks for its password in the same place.
 */
@Composable
internal fun PdfSourceSection(
    frame: PdfToolFrame,
    tint: Color,
    firstPage: suspend () -> Bitmap,
    onChoose: () -> Unit,
    onUnlock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!frame.isOpen) {
            FilePickCard(
                title = if (frame.opening) "Opening…" else "Choose a PDF",
                hint = "Pick one PDF from this phone",
                icon = EzIcons.PdfFile,
                tint = tint,
                onClick = { if (!frame.busy) onChoose() },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.5f).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PreviewTile(
                    key = "${frame.name}#${frame.pageCount}",
                    load = firstPage,
                    modifier = Modifier.size(width = 52.dp, height = 68.dp),
                    description = "First page",
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        frame.name.ifBlank { "PDF" },
                        style = MaterialTheme.typography.titleSmall,
                        color = EzColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(pdfMeta(frame), style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
                }
                EzTextButton(text = "Change", onClick = onChoose, enabled = !frame.busy)
            }
        }
        if (frame.needsPassword) {
            PdfUnlockCard(enabled = !frame.busy, onUnlock = onUnlock)
        }
    }
}

private fun pdfMeta(frame: PdfToolFrame): String = buildList {
    add("${frame.pageCount} ${if (frame.pageCount == 1) "page" else "pages"}")
    if (frame.signed) add("signed")
    if (frame.archival) add("archival")
}.joinToString(" · ")

/** The password prompt for a protected source. The password is kept in memory only, never in saved state. */
@Composable
private fun PdfUnlockCard(enabled: Boolean, onUnlock: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.55f).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("This PDF is protected", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text("PDF password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        PrimaryActionButton(
            text = "Unlock",
            icon = EzIcons.Lock,
            onClick = { onUnlock(password) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && password.isNotEmpty(),
        )
    }
}

/**
 * A choice as a glass card that turns glossy red when chosen. [leading] sits above the title, for a visual of the
 * choice (bars, a mini page, a file glyph).
 */
@Composable
internal fun ChoiceCard(
    title: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val ink = if (selected) EzColors.OnPrimary else EzColors.Text
    val secondary = if (selected) EzColors.OnPrimary.copy(alpha = 0.85f) else EzColors.TextSecondary
    Column(
        modifier
            .pressScale(interaction, 0.96f)
            .then(if (selected) Modifier.glossyPrimary(EzShapes.Card, 10.dp) else Modifier.glassCard(EzShapes.Card, 0.45f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
            ) {
                haptics.tick()
                onClick()
            }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading()
        Text(title, style = MaterialTheme.typography.titleSmall, color = ink)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = secondary)
    }
}

/** Keep-protection choice for a protected source, as two cards. */
@Composable
internal fun PdfProtectionCards(
    selected: PdfProtectionChoice,
    enabled: Boolean,
    onSelect: (PdfProtectionChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Password on the result", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PdfProtectionChoice.entries.forEach { choice ->
                ChoiceCard(
                    title = choice.label,
                    detail = if (choice == PdfProtectionChoice.SameAsOriginal) {
                        "Keeps the source's own settings."
                    } else {
                        "Protects the result again with AES-256."
                    },
                    selected = choice == selected,
                    enabled = enabled,
                    onClick = { onSelect(choice) },
                    modifier = Modifier.weight(1f),
                    leading = { Icon(EzIcons.Lock, contentDescription = null, tint = if (choice == selected) EzColors.OnPrimary else EzColors.Primary, modifier = Modifier.size(22.dp)) },
                )
            }
        }
    }
}

/** A glass card with a title, a line of text and an optional body; the result summary of a tool. */
@Composable
internal fun ReadyCard(title: String, notes: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.55f).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary) }
    }
}

/** A quiet error line in the accent colour. */
@Composable
internal fun ErrorLine(message: String, modifier: Modifier = Modifier) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = EzColors.Accent, modifier = modifier.padding(horizontal = 4.dp))
}

/** A password field that can show or hide its text. Both forms read the same [state]. */
@Composable
internal fun PasswordField(
    state: TextFieldState,
    label: String,
    shown: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (shown) {
        OutlinedTextField(
            state = state,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            lineLimits = TextFieldLineLimits.SingleLine,
            label = { Text(label) },
        )
    } else {
        OutlinedSecureTextField(
            state = state,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(label) },
        )
    }
}
