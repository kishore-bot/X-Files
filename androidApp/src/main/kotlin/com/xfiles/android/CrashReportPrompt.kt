package com.xfiles.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.crash.CrashReport
import com.xfiles.crash.CrashReports
import com.xfiles.design.EzColors
import com.xfiles.design.EzShapes
import com.xfiles.design.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * After a crash, asks once per launch whether to send the saved report. Nothing leaves the device
 * unless the user taps Send report and picks an app.
 */
@Composable
internal fun CrashReportPrompt(reports: CrashReports) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<CrashReport?>(null) }
    var checked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reports) {
        if (!checked) {
            report = withContext(Dispatchers.IO) { runCatching { reports.pending() }.getOrNull() }
            checked = true
        }
    }
    val current = report ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var shareFailed by rememberSaveable { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        report = null
        scope.launch(Dispatchers.IO) { reports.dismiss(current.id) }
    }
    AlertDialog(
        onDismissRequest = { report = null }, // Asked again next launch.
        shape = EzShapes.Dialog,
        title = { Text("Xfiles closed unexpectedly") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "A report was saved on this device. It has the app and Android versions, the device model, " +
                        "the time and the technical error, but none of your files, their names or their contents. " +
                        "It is sent only if you choose to.",
                )
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide report" else "View report") }
                if (expanded) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).glass(EzShapes.Small, 0.7f)
                            .verticalScroll(rememberScrollState()).padding(12.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                current.text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                                color = EzColors.Text,
                            )
                        }
                    }
                }
                if (shareFailed) {
                    Text("No app on this device can send it.", color = EzColors.Accent, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (reports.share(current)) dismiss() else shareFailed = true }) { Text("Send report") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Not now", color = EzColors.TextSecondary) } },
    )
}
