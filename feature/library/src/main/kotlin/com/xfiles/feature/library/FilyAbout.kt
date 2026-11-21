package com.xfiles.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.FilyMascot
import com.xfiles.design.FilyMascotState
import com.xfiles.fily.AssistantModel
import com.xfiles.fily.ModelInstall
import com.xfiles.fily.SpeechLanguageSupport

/**
 * What the About screen needs from Fily: the switches and the model, with the actions that change them.
 * Built by the app from the same stores the Fily overlay uses, so the two never disagree.
 */
data class FilyAbout(
    val enabled: Boolean,
    val contentAccess: Boolean,
    val onEnabled: (Boolean) -> Unit,
    val onContentAccess: (Boolean) -> Unit,
    val model: ModelInstall,
    val onDownload: () -> Unit,
    val onImport: (String) -> Unit,
    val onCancelDownload: () -> Unit,
    val onRemoveModel: () -> Unit,
    /** The speech language picker; null hides it (for example below Android 13, where the list is unknown). */
    val languages: FilyLanguages? = null,
)

/** Fily's speech language: what the on-device recognizer offers, the saved pick, and how to change it. */
data class FilyLanguages(
    val support: SpeechLanguageSupport,
    val selected: String?,
    val label: (String) -> String,
    val onSelect: (String) -> Unit,
    val onRefresh: () -> Unit,
)

/**
 * Fily's small mascot for the About screen's Fily card (anchored at the top of the card, 72dp).
 * AboutScreen's private FilyCard calls this; it is kept here so the card and dialog share one look.
 */
@Composable
fun FilyAboutMascot(modifier: Modifier = Modifier) {
    FilyMascot(modifier = modifier, state = FilyMascotState.Idle, size = 72.dp)
}

/** Megabytes as the download sizes are shown: 379 MB, not 379220000 bytes. */
internal fun megabytes(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"

/** Status and actions for the Fily model: download (with progress), import, cancel, or remove. */
@Composable
fun FilyModelRow(
    install: ModelInstall,
    bytes: Long,
    onDownload: () -> Unit,
    onImport: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    showHint: Boolean = true,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri.toString())
    }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val status = when (install) {
        ModelInstall.Missing -> "Not installed · ${megabytes(bytes)} download"
        is ModelInstall.Downloading -> "Downloading ${megabytes(install.done)} of ${megabytes(install.total)}"
        ModelInstall.Verifying -> "Checking the download…"
        ModelInstall.Installed -> "Installed on this phone · ${megabytes(bytes)}"
        is ModelInstall.Failed -> install.message
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(AssistantModel.Fily.label, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (install is ModelInstall.Failed) MaterialTheme.colorScheme.error else EzColors.TextSecondary,
        )
        if (install is ModelInstall.Downloading) {
            LinearProgressIndicator(
                progress = { if (install.total > 0) install.done.toFloat() / install.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (install == ModelInstall.Verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            when (install) {
                ModelInstall.Missing, is ModelInstall.Failed -> {
                    TextButton(onClick = onDownload) { Text("Download") }
                    TextButton(onClick = { pick.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Import model file") }
                }
                is ModelInstall.Downloading, ModelInstall.Verifying -> TextButton(onClick = onCancel) { Text("Cancel") }
                ModelInstall.Installed -> Unit
            }
            if (install == ModelInstall.Installed && onRemove != null) {
                TextButton(onClick = { confirmRemove = true }) { Text("Remove model", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (showHint && (install == ModelInstall.Missing || install is ModelInstall.Failed)) {
            Text("Wi-Fi is best · ${megabytes(bytes)}", style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
        }
    }
    if (confirmRemove && onRemove != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the Fily model?") },
            text = { Text("Fily stops answering until the model is downloaded or imported again. Your files are not affected.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep", color = EzColors.TextSecondary) } },
        )
    }
}

/**
 * Shown instead of the Fily overlay while the model isn't on the phone: a one-time download
 * (or an import of the file). Once installed, the caller shows Fily itself.
 */
@Composable
fun GetFilyDialog(
    install: ModelInstall,
    bytes: Long,
    onDownload: () -> Unit,
    onImport: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get Fily") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FilyMascot(size = 120.dp)
                Text(
                    "Fily runs entirely on your phone. Its AI model is a one-time ${megabytes(bytes)} download; " +
                        "after that, nothing you ask leaves the phone. Wi-Fi is best.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilyModelRow(install, bytes, onDownload, onImport, onCancel, showHint = false)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
