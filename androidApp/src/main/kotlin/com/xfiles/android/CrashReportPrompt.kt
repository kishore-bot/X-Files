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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzColors
import com.xfiles.design.EzShapes
import com.xfiles.design.glass

/** Review and send the latest saved report directly from Xfiles. */
@Composable
internal fun CrashReportPrompt(viewModel: CrashReportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state.report ?: return
    var expanded by rememberSaveable(current.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = viewModel::close,
        shape = EzShapes.Dialog,
        title = { Text("Xfiles closed unexpectedly") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (viewModel.canSend) {
                        "The report contains app and device details and a technical stack trace. " +
                            "You can send it directly to Xfiles."
                    } else {
                        "A technical report was saved on this phone. Sending is unavailable in this build."
                    },
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
                state.error?.let {
                    Text(it, color = EzColors.Accent, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            if (viewModel.canSend) {
                TextButton(onClick = viewModel::send, enabled = !state.sending) {
                    Text(if (state.sending) "Sending…" else "Send report")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss, enabled = !state.sending) {
                Text("Dismiss report", color = EzColors.TextSecondary)
            }
        },
    )
}
