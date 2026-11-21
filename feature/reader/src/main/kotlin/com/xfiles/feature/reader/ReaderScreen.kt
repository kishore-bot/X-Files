package com.xfiles.feature.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.design.EzIcons
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.PdfMarkup
import com.xfiles.documents.PdfMarkupKind
import com.xfiles.design.glassBar
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.xfiles.tidy.SensitiveFindings

@Composable
fun ReaderScreen(
    state: ReaderState,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onEdit: (String, String) -> Unit,
    onBack: () -> Unit,
    onPage: (Int) -> Unit,
    onSummary: () -> Unit,
    onDismissSummary: () -> Unit,
    onPassword: (String) -> Unit,
    pdfFind: PdfFindState,
    onPdfQuery: (String) -> Unit,
    onPdfMatch: (Int) -> Unit,
    onClosePdfFind: () -> Unit,
    markup: PdfMarkupState,
    onAddMarkup: (PdfMarkup) -> Unit,
    onUndoMarkup: () -> Unit,
    onPrepareMarkup: () -> Unit,
    onSaveMarkup: (android.net.Uri) -> Unit,
    onFily: () -> Unit,
    printing: PrintStatus = PrintStatus.Idle,
    printRequests: Flow<PrintJob> = emptyFlow(),
    onPrint: () -> Unit = {},
    onDismissPrintError: () -> Unit = {},
    sensitive: SensitiveFindings? = null,
    onShare: () -> Unit = {},
) {
    PrintRequests(printRequests)
    (printing as? PrintStatus.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = onDismissPrintError,
            title = { Text(failed.title) },
            text = { Text(failed.message) },
            confirmButton = { TextButton(onClick = onDismissPrintError) { Text("OK") } },
        )
    }
    val ready = state as? ReaderState.Ready
    val documentKey = ready?.file?.uri?.toString()
    var rotation by rememberSaveable(documentKey) { mutableIntStateOf(0) }
    var showSearch by rememberSaveable(documentKey) { mutableStateOf(false) }
    var showSource by rememberSaveable(documentKey) { mutableStateOf(false) }
    var showPdfFind by rememberSaveable(documentKey) { mutableStateOf(false) }
    var editingMarkup by rememberSaveable(documentKey) { mutableStateOf(false) }
    var mode by rememberSaveable(documentKey) { mutableStateOf(PdfMarkupKind.Pen) }
    var confirmDiscard by rememberSaveable(documentKey) { mutableStateOf(false) }
    val saveAnnotated = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) onSaveMarkup(uri)
    }
    val closeReader: () -> Unit = {
        if (!markup.saving) {
            if (markup.marks.isNotEmpty()) confirmDiscard = true else onBack()
        }
    }
    BackHandler(enabled = markup.marks.isNotEmpty() || markup.saving, onBack = closeReader)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = closeReader, enabled = !markup.saving) { Icon(EzIcons.Back, "Back to library") }
            Text(
                ready?.file?.name ?: "Reader",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ready != null && ready.preview != FilePreview.Unsupported) {
                IconButton(onClick = onBookmark) {
                    Icon(
                        if (bookmarked) EzIcons.Bookmark else EzIcons.BookmarkOutline,
                        if (bookmarked) "Remove bookmark" else "Add bookmark",
                    )
                }
            }
            if (!editingMarkup && (ready?.preview is FilePreview.Picture || ready?.preview is FilePreview.Pdf)) {
                IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                    Icon(EzIcons.Rotate, "Rotate clockwise")
                }
            }
            if (ready?.preview is FilePreview.Pdf) {
                IconButton(
                    onClick = {
                        editingMarkup = !editingMarkup
                        if (editingMarkup) {
                            showPdfFind = false
                            onClosePdfFind()
                        }
                    },
                    enabled = !markup.working,
                ) { Icon(if (editingMarkup) EzIcons.Close else EzIcons.Edit, if (editingMarkup) "Hide annotations" else "Annotate PDF") }
            }
            if (!editingMarkup && (ready?.preview as? FilePreview.Pdf)?.searchable == true) {
                IconButton(onClick = {
                    showPdfFind = !showPdfFind
                    if (!showPdfFind) onClosePdfFind()
                }) { Icon(EzIcons.Search, "Find in PDF") }
            }
            val text = when (val preview = ready?.preview) {
                is FilePreview.Text -> preview
                is FilePreview.Structured -> preview.source
                is FilePreview.Html -> preview.text
                else -> null
            }
            // Pages, JSON and code have a rich view and a plain-text view.
            val hasRichView = when (ready?.preview) {
                is FilePreview.Html -> true
                is FilePreview.Text -> ready.file.name.let { it.endsWith(".json", ignoreCase = true) || isSourceCode(it) }
                else -> false
            }
            if (hasRichView) {
                IconButton(onClick = {
                    showSearch = false
                    showSource = !showSource
                }) {
                    Icon(if (showSource) EzIcons.Eye else EzIcons.Code, if (showSource) "Show formatted" else "Show plain text")
                }
            }
            if (ready != null && text != null && ready.file.kind == DocumentKind.Text && !text.truncated) {
                IconButton(onClick = { onEdit(ready.file.name, text.content) }) {
                    Icon(EzIcons.Edit, "Edit text")
                }
            }
            if (ready?.preview is FilePreview.Structured && ready.preview.source != null) {
                IconButton(onClick = {
                    showSearch = false
                    showSource = !showSource
                }) {
                    Icon(if (showSource) EzIcons.Eye else EzIcons.Code, if (showSource) "Show preview" else "Show source")
                }
            }
            if (ready?.preview is FilePreview.Structured || (text != null && text.content.isNotBlank())) {
                IconButton(onClick = { showSearch = !showSearch }) { Icon(EzIcons.Search, "Find in file") }
                IconButton(onClick = onSummary) { Icon(EzIcons.Lightbulb, "At a glance") }
            }
            if (ready != null && !editingMarkup) IconButton(onClick = onFily) { Icon(EzIcons.Mic, "Voice assistant") }
            if (ready != null && !editingMarkup) {
                val printable = ready.file.kind !in setOf(DocumentKind.Audio, DocumentKind.Video, DocumentKind.Unknown)
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        if (printing == PrintStatus.Preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(EzIcons.More, "More options")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Share original") },
                            leadingIcon = { Icon(EzIcons.Share, null) },
                            onClick = { menu = false; onShare() },
                        )
                        if (printable) DropdownMenuItem(
                            text = { Text("Print") },
                            leadingIcon = { Icon(EzIcons.Print, null) },
                            enabled = printing != PrintStatus.Preparing,
                            onClick = { menu = false; onPrint() },
                        )
                    }
                }
            }
        }
        SensitiveFileHint(documentKey, sensitive, Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
        if (editingMarkup && ready?.preview is FilePreview.Pdf) {
            PdfMarkupControls(
                mode = mode,
                state = markup,
                onMode = { mode = it },
                onUndo = onUndoMarkup,
                onPrepare = onPrepareMarkup,
                onSave = { saveAnnotated.launch("${ready.file.name.substringBeforeLast('.')}-annotated.pdf") },
            )
        }
        if (showPdfFind && ready?.preview is FilePreview.Pdf) {
            PdfFindBar(pdfFind, onPdfQuery, onPdfMatch, onClose = {
                showPdfFind = false
                onClosePdfFind()
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            when (state) {
                ReaderState.Loading -> CircularProgressIndicator()
                ReaderState.ReadError -> Text("This file could not be opened. Try selecting it again.")
                ReaderState.EngineUnavailable -> Text("The document engine is unavailable on this device.")
                is ReaderState.Password -> {
                    Text(state.file.name)
                    DocumentPasswordDialog(state, onPassword, onBack)
                }
                is ReaderState.Failure -> Text(state.message)
                is ReaderState.Ready -> key(state.file.uri) {
                    when (val preview = state.preview) {
                        is FilePreview.Text -> {
                            val plain = showSearch || showSource
                            val json = remember(preview) {
                                if (state.file.name.endsWith(".json", ignoreCase = true)) parseJsonOrNull(preview.content) else null
                            }
                            when {
                                !plain && json != null -> JsonTreePreview(json)
                                !plain && isSourceCode(state.file.name) -> CodePreview(preview.content, state.file.name)
                                else -> TextPreview(preview, showSearch, onSearchClose = { showSearch = false })
                            }
                        }
                        is FilePreview.Html ->
                            if (showSearch || showSource) TextPreview(preview.text, showSearch, onSearchClose = { showSearch = false })
                            else HtmlPreview(preview.html)
                        is FilePreview.Structured -> {
                            if (showSearch || (showSource && preview.source != null)) {
                                val text = preview.source ?: remember(preview.document) {
                                    val content = preview.document.plainText(256_000)
                                    FilePreview.Text(content, preview.document.truncated || content.length >= 256_000)
                                }
                                TextPreview(text, showSearch, onSearchClose = { showSearch = false })
                            } else {
                                CompositionLocalProvider(LocalDocumentImages provides preview.document.images) {
                                    StructuredPreview(preview.document)
                                }
                            }
                        }
                        is FilePreview.Picture -> ReaderImage(preview.bitmap, state.file.name, rotation)
                        is FilePreview.Pdf -> {
                            if (editingMarkup) {
                                PdfMarkupCanvas(
                                    preview.bitmap,
                                    preview.page,
                                    mode,
                                    markup.marks.filter { it.page == preview.page },
                                    enabled = !markup.working && !state.turningPage,
                                    onAdd = onAddMarkup,
                                )
                            } else {
                                val active = pdfFind.matches.getOrNull(pdfFind.selected)
                                val highlights = active?.takeIf { showPdfFind && it.page == preview.page }?.boxes.orEmpty()
                                ReaderImage(preview.bitmap, "Page ${preview.page + 1}", rotation, highlights)
                            }
                        }
                        is FilePreview.Media -> MediaScreen(state.file, preview.video)
                        FilePreview.Unsupported -> ExternalOpen(state.file)
                    }
                }
            }
        }
        if (ready?.bookmarkError == true) {
            Text("This file provider does not allow Xfiles to keep access for bookmarks.", color = MaterialTheme.colorScheme.error)
        }
        val pdf = ready?.preview as? FilePreview.Pdf
        if (ready != null && pdf != null) {
            if (ready.turningPage) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ready.pageError) Text("This page could not be opened.", color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { onPage(pdf.page - 1) }, enabled = !ready.turningPage && pdf.page > 0) {
                    Icon(EzIcons.ChevronLeft, "Previous page")
                }
                Text("${pdf.page + 1} / ${pdf.pageCount}")
                IconButton(onClick = { onPage(pdf.page + 1) }, enabled = !ready.turningPage && pdf.page + 1 < pdf.pageCount) {
                    Icon(EzIcons.ChevronRight, "Next page")
                }
            }
        }
    }
    ready?.summary?.let { SummaryDialog(it, onDismissSummary) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved annotations?") },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
            confirmButton = { TextButton(onClick = onBack) { Text("Discard") } },
        )
    }
}

@Composable
private fun DocumentPasswordDialog(state: ReaderState.Password, onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    val password = remember(state.file.uri, state.incorrect) { TextFieldState() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock document") },
        text = {
            OutlinedSecureTextField(
                state = password,
                label = { Text("Password") },
                isError = state.incorrect,
                supportingText = if (state.incorrect) ({ Text("That password did not unlock the file.") }) else null,
            )
        },
        confirmButton = {
            TextButton(onClick = { onUnlock(password.text.toString()) }, enabled = password.text.isNotEmpty()) {
                Text("Unlock")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ExternalOpen(file: FileEntry, message: String = "An in-app preview is unavailable for this file.") {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message)
        TextButton(onClick = {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, context.contentResolver.getType(file.uri) ?: "application/octet-stream")
                clipData = ClipData.newRawUri(file.name, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Open with"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Open with") }
    }
}

@Composable
private fun SummaryDialog(state: SummaryState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("At a glance") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    SummaryState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    SummaryState.Error -> Text("This document could not be summarized.")
                    is SummaryState.Ready -> {
                        val summary = state.analysis
                        if (summary.wordCount > 0u) {
                            Text("${summary.wordCount} words \u00B7 ${summary.readingMinutes} min read")
                        }
                        if (summary.keySentences.isEmpty() && summary.keywords.isEmpty()) Text("No summary is available for this text.")
                        summary.keySentences.forEach { Text(it) }
                        if (summary.keywords.isNotEmpty()) Text(summary.keywords.joinToString(", "))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
