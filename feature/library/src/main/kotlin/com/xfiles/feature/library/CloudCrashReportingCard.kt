package com.xfiles.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xfiles.crash.CloudCrashReporting
import com.xfiles.design.EzColors
import com.xfiles.design.EzShapes
import com.xfiles.design.glassCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The opt-in switch for Firebase crash diagnostics. Shows in Firestore-enabled builds. */
@Composable
internal fun CloudCrashReportingCard(reports: CloudCrashReporting) {
    if (!reports.available) return
    val enabled by reports.enabled.collectAsState()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun save(value: Boolean) {
        if (saving) return
        saving = true
        failed = false
        scope.launch {
            try {
                reports.setEnabled(value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                saving = false
            }
        }
    }
    Column(Modifier.fillMaxWidth().glassCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Send crash diagnostics automatically", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Send technical crash reports to Xfiles via Firestore. Off by default.",
                    color = EzColors.TextSecondary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { if (!saving) { if (it) confirming = true else save(false) } },
            )
        }
        Text(
            when {
                saving -> "Saving your preference..."
                failed -> "Could not save the preference. Please try again."
                enabled -> "Future reports will be sent when Xfiles opens again."
                else -> "Xfiles asks before sending each report."
            },
            color = if (failed) EzColors.Accent else EzColors.TextSecondary,
        )
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        shape = EzShapes.Dialog,
        title = { Text("Allow crash diagnostics?") },
        text = {
            Text(
                "When Xfiles opens after a future crash, it can send the app version, device model, " +
                    "Android version and technical stack trace to the Xfiles team via Firestore. " +
                    "You can turn automatic uploads off here at any time.",
            )
        },
        confirmButton = { TextButton(onClick = { confirming = false; save(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep off", color = EzColors.TextSecondary) } },
    )
}
