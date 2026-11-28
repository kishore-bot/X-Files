package com.xfiles.feature.reader

import androidx.compose.runtime.Composable
import com.xfiles.design.EzDialog

/**
 * Asks before a link leaves the book for the web or an email app, in the EzFiles reader's popup.
 * Nothing opens until [onOpen].
 */
@Composable
internal fun BookExternalLinkSheet(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    EzDialog(
        onDismissRequest = onDismiss,
        title = "Open this link in your browser?",
        text = url,
        confirmText = "Open",
        onConfirm = onOpen,
        dismissText = "Cancel",
        onDismiss = onDismiss,
    )
}
