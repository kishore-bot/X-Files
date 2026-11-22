package com.xfiles.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzChip
import com.xfiles.design.EzColors
import com.xfiles.design.EzShapes
import com.xfiles.design.EzTextButton
import com.xfiles.design.glassCard
import com.xfiles.design.glossyPrimary
import com.xfiles.feedback.FeedbackCategory
import com.xfiles.feedback.FeedbackDestination
import com.xfiles.feedback.FeedbackRules

/**
 * The "Send feedback" sheet. Nothing is sent until the user presses Send. The sheet states where the report
 * goes, lists the device details it would attach, and never includes files, file names or documents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedbackSheet(onDismiss: () -> Unit, viewModel: FeedbackViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("title") {
                Text(
                    "Send feedback",
                    style = MaterialTheme.typography.titleLarge,
                    color = EzColors.Text,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    if (state.destination == FeedbackDestination.Cloud) PrivacyCloud else PrivacyShare,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item("category") {
                Row(
                    Modifier.semantics { contentDescription = "Feedback type" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FeedbackCategory.entries.forEach { category ->
                        EzChip(
                            label = category.label,
                            selected = state.category == category,
                            onClick = { viewModel.onCategory(category) },
                        )
                    }
                }
            }
            item("message") {
                OutlinedTextField(
                    value = state.message,
                    onValueChange = viewModel::onMessage,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your message") },
                    minLines = 4,
                    isError = state.messageTooLong,
                    supportingText = {
                        Text(
                            "${state.messageLength} / ${FeedbackRules.MAX_MESSAGE_LENGTH}",
                            color = if (state.messageTooLong) MaterialTheme.colorScheme.error else EzColors.TextSecondary,
                        )
                    },
                )
            }
            item("reply") {
                OutlinedTextField(
                    value = state.replyTo,
                    onValueChange = viewModel::onReplyTo,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reply email (optional)") },
                    singleLine = true,
                    isError = state.replyInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    supportingText = {
                        Text(
                            if (state.replyInvalid) "Enter an email address like name@example.com" else "Only if you want a reply",
                            color = if (state.replyInvalid) MaterialTheme.colorScheme.error else EzColors.TextSecondary,
                        )
                    },
                )
            }
            item("details") {
                Column(
                    Modifier.fillMaxWidth().glassCard(strength = 0.45f).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Checkbox) { viewModel.onIncludeDetails(!state.includeDetails) }
                            .semantics { contentDescription = "Include app version and device model" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.includeDetails, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Include app version and device model", style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
                    }
                    Text(
                        if (state.includeDetails) {
                            "Attached: ${viewModel.environment.summary()}"
                        } else {
                            "Nothing about this phone is attached."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                    )
                }
            }
            item("status") {
                StatusLine(state.status)
            }
            item("send") {
                SendButton(
                    enabled = state.canSend,
                    sending = state.status == FeedbackStatus.Sending,
                    cooldownSeconds = state.cooldownSeconds,
                    onClick = viewModel::send,
                )
            }
            if (state.status == FeedbackStatus.Sent || state.status == FeedbackStatus.Queued || state.status == FeedbackStatus.Shared) {
                item("done") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EzTextButton("Done", onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: FeedbackStatus) {
    val (text, color) = when (status) {
        FeedbackStatus.Idle, FeedbackStatus.Sending -> return
        FeedbackStatus.Sent -> "Thank you. Your feedback reached the Xfiles team." to EzColors.Text
        FeedbackStatus.Queued -> "Saved on this phone. It will be sent when you are back online." to EzColors.Text
        FeedbackStatus.Shared -> "Pick an app in the share sheet to finish sending." to EzColors.Text
        is FeedbackStatus.Failed -> status.reason to MaterialTheme.colorScheme.error
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SendButton(enabled: Boolean, sending: Boolean, cooldownSeconds: Int, onClick: () -> Unit) {
    val label = when {
        sending -> "Sending"
        cooldownSeconds > 0 -> "Send again in ${cooldownSeconds}s"
        else -> "Send"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .alpha(if (enabled || sending) 1f else 0.45f)
            .glossyPrimary(EzShapes.Pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = if (enabled) "Send feedback" else "Send feedback, unavailable: $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (sending) {
            CircularProgressIndicator(Modifier.size(18.dp), color = EzColors.OnPrimary, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = EzColors.OnPrimary, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

private const val PrivacyCloud = "Sent to the Xfiles team via Google Firebase. No files or file names are sent."
private const val PrivacyShare = "Opens your phone's share sheet, and you choose where it goes. No files or file names are sent."
