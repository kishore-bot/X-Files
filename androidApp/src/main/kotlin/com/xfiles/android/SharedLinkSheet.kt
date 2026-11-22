package com.xfiles.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.PrimaryActionButton
import com.xfiles.design.rememberHaptics
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

private val WEB_LINK = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)

/** True when shared text is a single web link (so it is offered as a link rather than opened as a note). */
fun isWebLink(text: String): Boolean = WEB_LINK.matches(text.trim())

/**
 * Shown when another app shares a single link to Xfiles. Offers: open the link, keep it as a `.txt` note
 * (opened in the editor, which saves it), or copy it.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun SharedLinkSheet(
    link: String,
    onSaveNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Shared link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                link,
                style = MaterialTheme.typography.bodyMedium,
                color = EzColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            PrimaryActionButton(
                text = "Open link",
                icon = null,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = {
                    haptics.tick()
                    onSaveNote()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save as note (.txt)") }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = {
                    haptics.tick()
                    copyToClipboard(context, link)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy") }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Xfiles", text))
}
