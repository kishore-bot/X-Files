package com.xfiles.feature.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.PdfAction
import com.xfiles.core.documents.PdfPaper
import com.xfiles.design.EzChip
import com.xfiles.design.EzColors
import com.xfiles.design.EzMotion
import com.xfiles.design.EzShapes
import com.xfiles.design.EzTextButton
import com.xfiles.design.glassCard

/**
 * The adjust step of a page-action tool, as list items after the source: a page-range card for extract and insert,
 * paper cards for page size, a new-password card with a strength meter for add password, and the source password
 * for any protected input.
 */
internal fun LazyListScope.pdfActionAdjust(
    action: PdfAction,
    enabled: Boolean,
    input: TextFieldState,
    sourcePassword: TextFieldState,
    newPassword: TextFieldState,
    confirmPassword: TextFieldState,
    paper: PdfPaper,
    onPaper: (PdfPaper) -> Unit,
    sourceLabel: String,
) {
    when (action) {
        PdfAction.Extract, PdfAction.Insert, PdfAction.Delete, PdfAction.Rotate -> item("pages") {
            PageRangeCard(action = action, input = input, enabled = enabled)
        }
        PdfAction.Resize -> item("paper") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Page size", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                PaperPicker(
                    selected = paper,
                    enabled = enabled,
                    onSelect = { chosen -> if (chosen != null) onPaper(chosen) },
                )
            }
        }
        PdfAction.Protect -> item("new-password") {
            NewPasswordCard(password = newPassword, confirm = confirmPassword, enabled = enabled)
        }
        PdfAction.Merge, PdfAction.Unlock -> Unit
    }
    item("source-password") { SourcePasswordCard(password = sourcePassword, enabled = enabled, label = sourceLabel) }
}

/** Which pages to keep or where to insert, typed or tapped from a suggestion. */
@Composable
private fun PageRangeCard(action: PdfAction, input: TextFieldState, enabled: Boolean) {
    val insert = action == PdfAction.Insert
    val suggestions = if (insert) listOf("1", "2", "3") else listOf("1", "1-3", "1, 3-5")
    val current = input.text.toString()
    Column(
        Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.5f).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (insert) "Insert before page" else "Pages to keep",
            style = MaterialTheme.typography.titleSmall,
            color = EzColors.Text,
        )
        OutlinedTextField(
            state = input,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            lineLimits = TextFieldLineLimits.SingleLine,
            label = { Text(if (insert) "Page number" else "Pages") },
            placeholder = { Text(if (insert) "1" else "1, 3-5") },
            supportingText = {
                Text(
                    if (insert) "The second PDF is placed before this page of the first." else "Pages come out in the order you type them.",
                )
            },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { value ->
                EzChip(
                    label = value,
                    selected = current == value,
                    onClick = { input.setTextAndPlaceCursorAtEnd(value) },
                )
            }
        }
    }
}

/** The new password and its confirmation. Both reveal together; the meter reads the first one. */
@Composable
private fun NewPasswordCard(password: TextFieldState, confirm: TextFieldState, enabled: Boolean) {
    var shown by remember { mutableStateOf(false) }
    val typed = password.text.toString()
    val again = confirm.text.toString()
    val mismatch = again.isNotEmpty() && again != typed
    Column(
        Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.5f).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "New password",
                style = MaterialTheme.typography.titleSmall,
                color = EzColors.Text,
                modifier = Modifier.weight(1f),
            )
            EzTextButton(text = if (shown) "Hide" else "Show", onClick = { shown = !shown }, enabled = enabled)
        }
        PasswordField(state = password, label = "New password", shown = shown, enabled = enabled)
        StrengthMeter(passwordStrength(typed))
        PasswordField(state = confirm, label = "Confirm password", shown = shown, enabled = enabled)
        Text(
            if (mismatch) "The two passwords don't match yet." else "At least 8 characters, with letters, numbers or symbols.",
            style = MaterialTheme.typography.bodySmall,
            color = if (mismatch) EzColors.Accent else EzColors.TextSecondary,
        )
    }
}

/** The password of a protected input, when the tool needs it. */
@Composable
private fun SourcePasswordCard(password: TextFieldState, enabled: Boolean, label: String) {
    var shown by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.45f).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Existing password",
                style = MaterialTheme.typography.titleSmall,
                color = EzColors.Text,
                modifier = Modifier.weight(1f),
            )
            EzTextButton(text = if (shown) "Hide" else "Show", onClick = { shown = !shown }, enabled = enabled)
        }
        PasswordField(state = password, label = label, shown = shown, enabled = enabled)
    }
}

/** A bar that fills as the password gets stronger, with its word beneath. */
@Composable
private fun StrengthMeter(strength: PasswordStrength) {
    val fraction by animateFloatAsState(
        targetValue = strength.fraction,
        animationSpec = EzMotion.defaultSpatial(),
        label = "passwordStrength",
    )
    val colour = when (strength) {
        PasswordStrength.None -> EzColors.TextSecondary
        PasswordStrength.Weak -> Color(0xFFE5484D)
        PasswordStrength.Fair -> Color(0xFFE0A100)
        PasswordStrength.Strong -> EzColors.Positive
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(EzShapes.Pill).background(EzColors.FieldOutline)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colour))
        }
        Text(strength.label, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

/** How strong a new password is, from its length and the kinds of characters in it. */
internal enum class PasswordStrength(val label: String, val fraction: Float) {
    None("Enter a password", 0f),
    Weak("Weak", 0.33f),
    Fair("Fair", 0.66f),
    Strong("Strong", 1f),
}

internal fun passwordStrength(password: String): PasswordStrength {
    if (password.isEmpty()) return PasswordStrength.None
    var score = 0
    if (password.length >= 8) score++
    if (password.length >= 12) score++
    if (password.any { it.isLowerCase() } && password.any { it.isUpperCase() }) score++
    if (password.any { it.isDigit() }) score++
    if (password.any { !it.isLetterOrDigit() }) score++
    return when {
        score <= 1 -> PasswordStrength.Weak
        score <= 3 -> PasswordStrength.Fair
        else -> PasswordStrength.Strong
    }
}
