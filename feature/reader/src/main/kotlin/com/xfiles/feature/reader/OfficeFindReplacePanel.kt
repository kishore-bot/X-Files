package com.xfiles.feature.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.OfficeEditRepository
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar

/** Whether the reader offers find and replace for [name]. */
fun isOfficeEditable(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in OfficeEditRepository.EDITABLE_EXTENSIONS

private fun officeMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "odp" -> "application/vnd.oasis.opendocument.presentation"
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
    else -> "application/vnd.oasis.opendocument.text"
}

/**
 * Find and replace for one office document. [onPreview] receives each edited
 * preview, and null when the edits are dropped and the original should show again.
 * The original file is never written; "Save a copy" asks the user for a destination.
 */
@Composable
fun OfficeFindReplacePanel(
    uri: Uri,
    name: String,
    onPreview: (FilePreview?) -> Unit,
    onClose: () -> Unit,
) {
    val viewModel: OfficeFindReplaceViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    LaunchedEffect(uri) { viewModel.bind(uri, name) }
    LaunchedEffect(viewModel) { viewModel.previews.collect { onPreview(it) } }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(officeMimeType(name))) { destination ->
        if (destination != null) viewModel.saveCopy(destination)
    }
    val close: () -> Unit = {
        if ((state as? OfficeEditUiState.Ready)?.edited == true) {
            viewModel.discardEdits()
            onPreview(null)
        }
        onClose()
    }

    Column(Modifier.fillMaxWidth().glassBar().padding(12.dp)) {
        when (val current = state) {
            OfficeEditUiState.Preparing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Preparing document", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = close) { Icon(EzIcons.Close, "Close find and replace") }
            }
            is OfficeEditUiState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = close) { Icon(EzIcons.Close, "Close find and replace") }
            }
            is OfficeEditUiState.Ready -> ReadyContent(
                state = current,
                onFind = viewModel::onFind,
                onReplacement = viewModel::onReplacement,
                onMatchCase = viewModel::onMatchCase,
                onWholeWord = viewModel::onWholeWord,
                showNumberOption = name.substringAfterLast('.', "").equals("ods", ignoreCase = true),
                onIncludeNumberCells = viewModel::onIncludeNumberCells,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onReplaceCurrent = viewModel::replaceCurrent,
                onReplaceAll = viewModel::replaceAll,
                onSaveCopy = { saveCopy.launch(viewModel.suggestedCopyName()) },
                onClose = close,
            )
        }
    }
}

@Composable
private fun ReadyContent(
    state: OfficeEditUiState.Ready,
    onFind: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onMatchCase: (Boolean) -> Unit,
    onWholeWord: (Boolean) -> Unit,
    showNumberOption: Boolean,
    onIncludeNumberCells: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplaceCurrent: () -> Unit,
    onReplaceAll: () -> Unit,
    onSaveCopy: () -> Unit,
    onClose: () -> Unit,
) {
    val found = state.matches as? OfficeMatches.Found
    val hasMatches = found != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.form.find,
            onValueChange = onFind,
            label = { Text("Find") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) { Icon(EzIcons.Close, "Close find and replace") }
    }
    OutlinedTextField(
        value = state.form.replacement,
        onValueChange = onReplacement,
        label = { Text("Replace with") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.form.matchCase,
            onClick = { onMatchCase(!state.form.matchCase) },
            label = { Text("Match case") },
        )
        FilterChip(
            selected = state.form.wholeWord,
            onClick = { onWholeWord(!state.form.wholeWord) },
            label = { Text("Whole word") },
        )
        if (showNumberOption) {
            FilterChip(
                selected = state.form.includeNumberCells,
                onClick = { onIncludeNumberCells(!state.form.includeNumberCells) },
                label = { Text("Number cells") },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val matches = state.matches) {
            OfficeMatches.Idle -> Text("", style = MaterialTheme.typography.labelLarge)
            is OfficeMatches.Searching -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (matches.totalParts > 0) {
                    Text(
                        "  ${matches.scannedParts} of ${matches.totalParts} parts",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            OfficeMatches.NoMatches -> Text("No matches", style = MaterialTheme.typography.labelLarge)
            is OfficeMatches.Found -> Text(
                "${matches.index + 1} of ${matches.total}${if (matches.truncated) "+" else ""}",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onPrevious, enabled = hasMatches && !state.busy) { Text("Prev") }
        TextButton(onClick = onNext, enabled = hasMatches && !state.busy) { Text("Next") }
    }
    found?.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
        Text(
            snippet,
            Modifier.fillMaxWidth().padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onReplaceCurrent, enabled = hasMatches && !state.busy) { Text("Replace") }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onReplaceAll, enabled = hasMatches && !state.busy) { Text("Replace all") }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onSaveCopy, enabled = state.edited && !state.busy) { Text("Save a copy") }
    }
    if (state.busy) {
        Text("Working", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
    state.message?.let { message ->
        Text(
            message,
            Modifier.fillMaxWidth().padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
