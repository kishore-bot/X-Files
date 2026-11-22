package com.xfiles.feature.reader

import androidx.compose.material3.TextButton
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.xfiles.core.documents.PdfPageText
import com.xfiles.design.EzIcons
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.PdfBox
import com.xfiles.documents.PdfMarkup
import com.xfiles.documents.PdfMarkupKind
import com.xfiles.design.BarIconButton
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
    onTwoPage: (Boolean) -> Unit = {},
    pdfThumbnail: suspend (Int) -> Bitmap? = { null },
    /** Opens a file extracted from the archive on screen; wired to DocumentAnalysisViewModel.openNested. */
    onOpenArchiveEntry: (Uri) -> Unit = {},
    /** Returns from a nested archive entry; false when there is none, so the reader closes. */
    onNestedBack: () -> Boolean = { false },
    gallery: GalleryUiState = GalleryUiState.Off,
    onGalleryPage: (Int) -> Unit = {},
    galleryPage: suspend (Int, Int) -> Bitmap? = { _, _ -> null },
    galleryAnimation: suspend (Int, Int) -> AnimatedImageDrawable? = { _, _ -> null },
    pictureAnimation: suspend (FileEntry, Int) -> AnimatedImageDrawable? = { _, _ -> null },
    gallerySort: GallerySort = GallerySort.Name,
    onGallerySort: (GallerySort) -> Unit = {},
    rotationOf: (String) -> Int = { 0 },
    onRotate: (String) -> Unit = {},
    onEditedPreview: (FilePreview?) -> Unit = {},
    /** Opens the text file at the URI in the editor, which reads its bytes for encoding detection. */
    onEditFile: ((Uri, String) -> Unit)? = null,
    /** Cell and slide editing for the open spreadsheet or presentation. */
    structuredEdits: StructuredEditSession? = null,
    /** Character geometry of a PDF page for text selection; see DocumentAnalysisViewModel.pdfText. */
    pdfText: suspend (Int) -> PdfPageText? = { null },
) {
    PrintRequests(printRequests)
    val context = LocalContext.current
    (printing as? PrintStatus.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = onDismissPrintError,
            title = { Text(failed.title) },
            text = { Text(failed.message) },
            confirmButton = { TextButton(onClick = onDismissPrintError) { Text("OK") } },
        )
    }
    val ready = state as? ReaderState.Ready
    // A gallery of two or more pictures; a single picture keeps the plain viewer.
    val paged = (gallery as? GalleryUiState.Paged)?.session?.takeIf { it.size > 1 }
    val documentKey = ready?.file?.uri?.toString()
    // Quarter turns belong to each picture (the view model keeps them), so a gallery page keeps its own turn.
    val rotation = documentKey?.let(rotationOf) ?: 0
    var sortMenu by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable(documentKey) { mutableStateOf(false) }
    var showSource by rememberSaveable(documentKey) { mutableStateOf(false) }
    var reading by rememberSaveable(documentKey) { mutableStateOf(ReadingTheme.Standard) }
    var showPdfFind by rememberSaveable(documentKey) { mutableStateOf(false) }
    var editingMarkup by rememberSaveable(documentKey) { mutableStateOf(false) }
    var mode by rememberSaveable(documentKey) { mutableStateOf(PdfMarkupKind.Pen) }
    var confirmDiscard by rememberSaveable(documentKey) { mutableStateOf(false) }
    var showJump by rememberSaveable(documentKey) { mutableStateOf(false) }
    var showOffice by rememberSaveable(documentKey) { mutableStateOf(false) }
    var showGrid by rememberSaveable(documentKey) { mutableStateOf(false) }
    val structuredUi by (structuredEdits?.state ?: kotlinx.coroutines.flow.emptyFlow<StructuredEditState>()).collectAsState(StructuredEditState())
    val saveEdited = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(structuredMimeType(ready?.file?.name.orEmpty()))) { uri ->
        if (uri != null) structuredEdits?.saveCopy(uri)
    }
    val saveAnnotated = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) onSaveMarkup(uri)
    }
    val closeReader: () -> Unit = {
        if (!markup.saving) {
            if (markup.marks.isNotEmpty()) confirmDiscard = true else if (!onNestedBack()) onBack()
        }
    }
    BackHandler(enabled = markup.marks.isNotEmpty() || markup.saving, onBack = closeReader)
    BackHandler(enabled = showGrid, onBack = { showGrid = false })
    // Picture-in-Picture shows only the picture: no bars, hints or panels, and no margins.
    val inPip by LocalMediaPip.current.isInPictureInPicture.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = if (inPip) 0.dp else 12.dp, vertical = if (inPip) 0.dp else 8.dp)) {
        if (!inPip) Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically) {
            BarIconButton(EzIcons.Back, "Back to library", closeReader, enabled = !markup.saving)
            Text(
                ready?.file?.name ?: "Reader",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            paged?.let { session ->
                Text(
                    "${session.index + 1} / ${session.size}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(EzIcons.ListLines, "Sort: ${gallerySort.label}")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        GallerySort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option == gallerySort) "${option.label} (current)" else option.label) },
                                onClick = {
                                    sortMenu = false
                                    onGallerySort(option)
                                },
                            )
                        }
                    }
                }
            }
            if (ready != null && ready.preview != FilePreview.Unsupported) {
                IconButton(onClick = onBookmark) {
                    Icon(
                        if (bookmarked) EzIcons.Bookmark else EzIcons.BookmarkOutline,
                        if (bookmarked) "Remove bookmark" else "Add bookmark",
                    )
                }
            }
            if (!editingMarkup && (ready?.preview is FilePreview.Picture || ready?.preview is FilePreview.Pdf)) {
                IconButton(onClick = { documentKey?.let(onRotate) }) {
                    Icon(EzIcons.Rotate, "Rotate clockwise")
                }
            }
            if (ready?.preview is FilePreview.Pdf) {
                IconButton(
                    onClick = {
                        editingMarkup = !editingMarkup
                        if (editingMarkup) {
                            showPdfFind = false
                            showGrid = false
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
                IconButton(onClick = {
                    val openFile = onEditFile
                    if (openFile != null) openFile(ready.file.uri, ready.file.name) else onEdit(ready.file.name, text.content)
                }) {
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
            if (!editingMarkup && ready != null && ready.preview is FilePreview.Structured && isOfficeEditable(ready.file.name)) {
                IconButton(onClick = { showOffice = !showOffice }) { Icon(EzIcons.Edit, "Find and replace") }
            }
            if (ready?.preview is FilePreview.Structured || (text != null && text.content.isNotBlank())) {
                IconButton(onClick = { showSearch = !showSearch }) { Icon(EzIcons.Search, "Find in file") }
                IconButton(onClick = onSummary) { Icon(EzIcons.Lightbulb, "At a glance") }
            } else if (ready?.preview is FilePreview.Pdf && !editingMarkup) {
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
                        DropdownMenuItem(
                            text = { Text("Page: ${reading.name} reading") },
                            leadingIcon = { Icon(EzIcons.Eye, null) },
                            onClick = { menu = false; reading = reading.next() },
                        )
                        if (structuredUi.edited) DropdownMenuItem(
                            text = { Text("Save edited copy") },
                            leadingIcon = { Icon(EzIcons.Share, null) },
                            onClick = { menu = false; saveEdited.launch(ready?.file?.name.orEmpty()) },
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
        if (!inPip) SensitiveFileHint(documentKey, sensitive, Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
        if (!inPip && showOffice && ready != null && isOfficeEditable(ready.file.name)) {
            OfficeFindReplacePanel(
                uri = ready.file.uri,
                name = ready.file.name,
                onPreview = onEditedPreview,
                onClose = { showOffice = false },
            )
        }
        if (!inPip && editingMarkup && ready?.preview is FilePreview.Pdf) {
            PdfMarkupControls(
                mode = mode,
                state = markup,
                onMode = { mode = it },
                onUndo = onUndoMarkup,
                onPrepare = onPrepareMarkup,
                onSave = { saveAnnotated.launch("${ready.file.name.substringBeforeLast('.')}-annotated.pdf") },
            )
        }
        if (!inPip && showPdfFind && ready?.preview is FilePreview.Pdf) {
            PdfFindBar(pdfFind, onPdfQuery, onPdfMatch, onClose = {
                showPdfFind = false
                onClosePdfFind()
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            val gridPdf = ready?.preview as? FilePreview.Pdf
            if (showGrid && gridPdf != null) {
                PdfPageGrid(
                    pageCount = gridPdf.pageCount,
                    current = gridPdf.page,
                    load = pdfThumbnail,
                    onOpen = { index ->
                        showGrid = false
                        onPage(index)
                    },
                    onClose = { showGrid = false },
                )
            } else when (state) {
                ReaderState.Loading -> CircularProgressIndicator()
                ReaderState.ReadError -> Text("This file could not be opened. Try selecting it again.")
                ReaderState.EngineUnavailable -> Text("The document engine is unavailable on this device.")
                is ReaderState.Password -> {
                    Text(state.file.name)
                    DocumentPasswordDialog(state, onPassword, onBack)
                }
                is ReaderState.Failure -> Text(state.message)
                // A gallery keeps one key across page turns, so the pager is not recreated when the file changes.
                is ReaderState.Ready -> key(paged?.id ?: state.file.uri) {
                    when (val preview = state.preview) {
                        is FilePreview.Text -> {
                            val plain = showSearch || showSource
                            val json = remember(preview) {
                                if (state.file.name.endsWith(".json", ignoreCase = true)) parseJsonOrNull(preview.content) else null
                            }
                            when {
                                !plain && json != null -> JsonTreePreview(json)
                                !plain && isSourceCode(state.file.name) -> CodePreview(preview.content, state.file.name, reading)
                                else -> TextPreview(preview, showSearch, onSearchClose = { showSearch = false }, reading = reading)
                            }
                        }
                        is FilePreview.Html ->
                            if (showSearch || showSource) TextPreview(preview.text, showSearch, onSearchClose = { showSearch = false }, reading = reading)
                            else HtmlPreview(preview.html, reading)
                        is FilePreview.Structured -> if (isZipName(state.file.name)) {
                            ArchiveBrowser(archive = state.file.uri, onOpenFile = onOpenArchiveEntry)
                        } else {
                            if (showSearch || (showSource && preview.source != null)) {
                                val text = preview.source ?: remember(preview.document) {
                                    val content = preview.document.plainText(256_000)
                                    FilePreview.Text(content, preview.document.truncated || content.length >= 256_000)
                                }
                                TextPreview(text, showSearch, onSearchClose = { showSearch = false }, reading = reading)
                            } else {
                                CompositionLocalProvider(LocalDocumentImages provides preview.document.images) {
                                    StructuredPreview(preview.document, structuredEdits)
                                }
                            }
                        }
                        is FilePreview.Picture -> if (paged != null) {
                            val seeded = gallery as GalleryUiState.Paged
                            GalleryPager(
                                session = paged,
                                seed = seeded.seed,
                                seedUri = seeded.seedUri,
                                rotationOf = rotationOf,
                                onSettle = onGalleryPage,
                                load = galleryPage,
                                animate = galleryAnimation,
                            )
                        } else {
                            // GIF, WebP and AVIF play here too; stills get null and stay on the bitmap.
                            val animation by produceState<AnimatedImageDrawable?>(null, state.file.uri, preview.bitmap) {
                                value = try {
                                    pictureAnimation(state.file, maxOf(preview.bitmap.width, preview.bitmap.height))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            ReaderImage(
                                bitmap = preview.bitmap,
                                description = state.file.name,
                                rotation = rotation,
                                regions = rememberPictureRegions(state.file.uri),
                                animated = animation,
                                animating = true,
                            )
                        }
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
                                fun highlights(page: Int) = active?.takeIf { showPdfFind && it.page == page }?.boxes.orEmpty()
                                // Highlights made in this session show on their page; the marks save with the annotated copy.
                                fun marksOn(page: Int): List<PdfBox> = markup.marks.asSequence()
                                    .filterIsInstance<PdfMarkup.Highlight>()
                                    .filter { it.page == page }
                                    .flatMap { it.boxes.asSequence() }
                                    .toList()
                                fun textSource(page: Int): PdfTextSource = PdfTextSource(
                                    page = page,
                                    load = { pdfText(page) },
                                    onHighlight = { boxes -> onAddMarkup(PdfMarkup.Highlight(page, boxes)) },
                                    onSearch = { query ->
                                        showPdfFind = true
                                        onPdfQuery(query)
                                    },
                                )
                                val facing = state.facing
                                if (state.twoPage && facing != null) {
                                    PdfSpread(preview, facing, rotation, ::highlights, ::textSource, ::marksOn)
                                } else {
                                    ReaderImage(
                                        preview.bitmap,
                                        "Page ${preview.page + 1}",
                                        rotation,
                                        highlights(preview.page),
                                        text = textSource(preview.page),
                                        marks = marksOn(preview.page),
                                    )
                                }
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
            PdfPageBar(
                page = pdf.page,
                pageCount = pdf.pageCount,
                twoPage = ready.twoPage,
                busy = ready.turningPage,
                canSpread = !editingMarkup,
                onPage = onPage,
                onJump = { showJump = true },
                onTwoPage = onTwoPage,
                onGrid = { showGrid = true },
            )
            if (showJump) {
                PdfJumpDialog(pdf.page, pdf.pageCount, onJump = onPage, onDismiss = { showJump = false })
            }
        }
    }
    val summaryState = ready?.summary
    if (ready != null && summaryState != null) {
        val inPdf = ready.preview is FilePreview.Pdf
        SummaryDialog(
            state = summaryState,
            documentName = ready.file.name,
            onReference = { query ->
                if (inPdf) {
                    showPdfFind = true
                    onPdfQuery(query)
                } else {
                    copyToClipboard(context, query)
                }
            },
            onDismiss = onDismissSummary,
        )
    }
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
private fun SummaryDialog(
    state: SummaryState,
    documentName: String,
    onReference: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                        if (summary.items.isNotEmpty()) GlanceItemGroups(summary.items, documentName, onReference)
                        if (summary.keySentences.isEmpty() && summary.keywords.isEmpty() && summary.items.isEmpty()) {
                            Text("No summary is available for this text.")
                        }
                        summary.keySentences.forEach { Text(it) }
                        if (summary.keywords.isNotEmpty()) Text(summary.keywords.joinToString(", "))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
