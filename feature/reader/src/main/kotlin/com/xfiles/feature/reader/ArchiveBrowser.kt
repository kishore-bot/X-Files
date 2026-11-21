package com.xfiles.feature.reader

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xfiles.core.documents.dosDateTimeMillis
import com.xfiles.documents.archive.ArchiveNode
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import com.xfiles.design.glassCard
import java.text.DateFormat
import java.util.Date

/** Files the reader sends to the archive browser. Other ZIP-based formats (DOCX, EPUB, ...) keep their own views. */
internal fun isZipName(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)

/**
 * Folder browser for a ZIP archive.
 *
 * Folders come first, then names in natural order. Tapping a file extracts
 * just that entry to the app cache and hands the reader a `content://` URI;
 * backing out of the reader returns here at the same folder. Extraction to a
 * user-picked folder runs with progress and a cancel button.
 *
 * [onOpenFile] is called on Main with the cached entry's URI.
 */
@Composable
fun ArchiveBrowser(
    archive: Uri,
    onOpenFile: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ArchiveViewModel = hiltViewModel(key = "archive:$archive")
    LaunchedEffect(archive) { vm.open(archive) }
    val state by vm.state.collectAsState()
    val activity by vm.activity.collectAsState()
    val prompt by vm.prompt.collectAsState()
    val browse = (state as? ArchiveUiState.Ready)?.browse

    var pendingExportFolder by rememberSaveable { mutableStateOf<String?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val folder = pendingExportFolder
        pendingExportFolder = null
        if (treeUri != null && folder != null) vm.exportFolder(folder, treeUri)
    }

    // Back climbs out of search first, then out of folders; at the root the reader handles it.
    BackHandler(enabled = browse != null && (browse.query.isNotEmpty() || browse.folderPath.isNotEmpty())) {
        if (browse?.query?.isNotEmpty() == true) vm.setQuery("") else vm.up()
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        when (val current = state) {
            ArchiveUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ArchiveUiState.Failed -> Text(current.message, modifier = Modifier.padding(16.dp))
            is ArchiveUiState.Ready -> {
                val view = current.browse
                Column(Modifier.fillMaxWidth().glassBar().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Breadcrumbs(view, onRoot = { vm.enter("") }, onCrumb = { vm.enter(it) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchField(
                            query = view.query,
                            onQuery = { vm.setQuery(it) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            pendingExportFolder = ""
                            pickFolder.launch(null)
                        }) {
                            Icon(EzIcons.Archive, contentDescription = "Extract everything to a folder")
                        }
                    }
                }
                ActivityBanner(activity, onCancel = { vm.cancelWork() }, onDismiss = { vm.dismissNotice() })
                prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onDismiss = vm::cancelPassword) }
                val listed = if (view.query.isBlank()) view.rows else view.hits
                if (listed.isEmpty()) {
                    Text(
                        if (view.query.isBlank()) "This folder is empty." else "No entries match \"${view.query}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EzColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    ) {
                        items(listed, key = { it.path }) { node ->
                            val inSearch = view.query.isNotBlank()
                            ArchiveRow(
                                node = node,
                                showPath = inSearch,
                                onOpen = {
                                    val entry = node.entry
                                    when {
                                        node.isDirectory && inSearch -> {
                                            vm.setQuery("")
                                            vm.enter(node.path)
                                        }
                                        node.isDirectory -> vm.enter(node.path)
                                        entry != null -> vm.openFile(entry.path, entry.encrypted, onOpenFile)
                                    }
                                },
                                onExtract = if (node.isDirectory) {
                                    {
                                        pendingExportFolder = node.path
                                        pickFolder.launch(null)
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(view: ArchiveBrowse, onRoot: () -> Unit, onCrumb: (String) -> Unit) {
    LazyRow(verticalAlignment = Alignment.CenterVertically) {
        item(key = "root") {
            TextButton(onClick = onRoot) { Text("Archive") }
        }
        items(view.crumbs, key = { it.path }) { crumb ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("/", color = EzColors.TextSecondary)
                TextButton(onClick = { onCrumb(crumb.path) }) { Text(crumb.name, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    // Local text keeps typing immediate; the ViewModel debounces the actual search.
    var text by rememberSaveable(query) { mutableStateOf(query) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onQuery(it)
        },
        singleLine = true,
        placeholder = { Text("Search this archive") },
        leadingIcon = { Icon(EzIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = {
                    text = ""
                    onQuery("")
                }) { Icon(EzIcons.Close, contentDescription = "Clear search") }
            }
        },
        modifier = modifier,
    )
}

/**
 * Asks for the archive password. The text is never logged or saved; the
 * ViewModel keeps a working password for this session only.
 */
@Composable
private fun PasswordDialog(prompt: PasswordPrompt, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password required") },
        text = {
            Column {
                Text(
                    if (prompt.wrong) "That password did not work. Try again."
                    else "This archive is password-protected. The password is kept only while this archive is open.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(password) }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActivityBanner(activity: ArchiveActivity, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (val current = activity) {
        ArchiveActivity.Idle -> Unit
        is ArchiveActivity.Working -> Column(Modifier.fillMaxWidth().glassCard().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            val progress = current.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
        is ArchiveActivity.Notice -> Row(
            Modifier.fillMaxWidth().glassCard().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun ArchiveRow(node: ArchiveNode, showPath: Boolean, onOpen: () -> Unit, onExtract: (() -> Unit)?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val entry = node.entry
    val encrypted = entry?.encrypted == true
    Row(
        Modifier
            .fillMaxWidth()
            .glassCard()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                node.isDirectory -> EzIcons.Folder
                encrypted -> EzIcons.Lock
                else -> EzIcons.Document
            },
            contentDescription = null,
            tint = EzColors.Primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (showPath) node.path else node.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                details(node, context),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = EzColors.TextSecondary,
            )
        }
        if (onExtract != null) {
            IconButton(onClick = onExtract) {
                Icon(EzIcons.Archive, contentDescription = "Extract ${node.name} to a folder")
            }
        }
    }
}

/** Size, saving and date for one row. Folders and encrypted files show only what applies. */
private fun details(node: ArchiveNode, context: Context): String {
    val entry = node.entry ?: return "Folder"
    if (node.isDirectory) return "Folder"
    if (entry.encrypted) return "Encrypted"
    val parts = ArrayList<String>(3)
    parts += Formatter.formatShortFileSize(context, entry.size)
    if (entry.size > 0 && entry.compressedSize in 0 until entry.size) {
        val saved = 100 - (entry.compressedSize * 100 / entry.size)
        parts += "compressed $saved%"
    }
    dosDateTimeMillis(entry.modifiedDos)?.let { parts += DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
    return parts.joinToString(" · ")
}
