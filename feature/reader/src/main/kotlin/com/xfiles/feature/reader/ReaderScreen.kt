package com.xfiles.feature.reader

import kotlinx.coroutines.delay
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfPageText
import com.xfiles.design.EzIcons
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.PdfMarkup
import com.xfiles.documents.structure.DocumentFormat
import com.xfiles.feature.reader.domain.isInDeviceStorage
import com.xfiles.tidy.SensitiveFindings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * The document reader. Stateful only at the edges: the state, flags and lambdas come in from the activity
 * and [ReaderUiViewModel]. The top bar holds Back and the name; every other action is in the floating
 * action button, which the content's gestures show and hide.
 */
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
    /** One page of the open PDF for the continuous view and its thumbnails; see DocumentAnalysisViewModel.pagePreview. */
    pagePreview: suspend (Int) -> FilePreview.Pdf? = { null },
    /** Registers the HTML or spreadsheet viewer for the assistant's scroll commands; see DocumentAnalysisViewModel.attachViewerScroll. */
    onAttachScroll: (ViewerScroll) -> () -> Unit = { {} },
) {
    PrintRequests(printRequests)
    val context = LocalContext.current
    val haptics = rememberHaptics()
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
    val fileName = ready?.file?.name.orEmpty()
    // Quarter turns belong to each picture (the view model keeps them), so a gallery page keeps its own turn.
    val rotation = documentKey?.let(rotationOf) ?: 0

    val chromeVm: ReaderUiViewModel = hiltViewModel()
    val chromeState by chromeVm.state.collectAsStateWithLifecycle()
    val ui = chromeState.forDocument(documentKey)
    LaunchedEffect(documentKey) { chromeVm.onEvent(ReaderUiEvent.DocumentChanged(documentKey)) }
    val onEvent: (ReaderUiEvent) -> Unit = chromeVm::onEvent

    // The page style is chosen per file type (its extension) and remembered in preferences.
    var pageStyle by rememberSaveable(documentKey) { mutableStateOf(readPageStyle(context, pageStyleKey(fileName))) }
    // The text size of plain and code views is remembered per kind of viewer, not per file.
    val textKind = if (isSourceCode(fileName) || isSvgName(fileName)) "code" else "text"
    var textScale by rememberSaveable(textKind) { mutableStateOf(readTextScale(context, textKind)) }
    val saveVm: SaveToDownloadsViewModel = hiltViewModel()
    val saveState by saveVm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        saveVm.onEvent(if (granted) SaveUiEvent.PermissionGranted else SaveUiEvent.PermissionDenied)
    }
    LaunchedEffect(saveState.needsPermission) {
        if (saveState.needsPermission) storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    LaunchedEffect(saveState.message) {
        val text = saveState.message ?: return@LaunchedEffect
        saveVm.onEvent(SaveUiEvent.Consumed)
        snackScope.launch { snackbar.showSnackbar(text) }
    }
    val structuredUi by (structuredEdits?.state ?: kotlinx.coroutines.flow.emptyFlow<StructuredEditState>()).collectAsState(StructuredEditState())
    val saveEdited = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(structuredMimeType(fileName))) { uri ->
        if (uri != null) structuredEdits?.saveCopy(uri)
    }
    val saveAnnotated = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) onSaveMarkup(uri)
    }
    val closeReader: () -> Unit = {
        if (!markup.saving) {
            if (markup.marks.isNotEmpty()) onEvent(ReaderUiEvent.SetConfirmDiscard(true)) else if (!onNestedBack()) onBack()
        }
    }
    BackHandler(enabled = ui.actionsOpen) { onEvent(ReaderUiEvent.SetActionsOpen(false)) }
    BackHandler(enabled = markup.marks.isNotEmpty() || markup.saving, onBack = closeReader)
    BackHandler(enabled = ui.showGrid, onBack = { onEvent(ReaderUiEvent.SetGrid(false)) })
    // Picture-in-Picture shows only the picture: no bars, hints or panels, and no margins.
    val inPip by LocalMediaPip.current.isInPictureInPicture.collectAsStateWithLifecycle()
    // Video and audio take the whole screen, with their own close button, as in EzFiles.
    val media = ready?.preview as? FilePreview.Media
    if (ready != null && media != null) {
        MediaScreen(ready.file, media.video, onClose = closeReader)
        return
    }
    // An EPUB book has its own chrome (with Fily), so the reader's bar and action button stay away from it.
    val epub = (ready?.preview as? FilePreview.Structured)?.document?.info?.format == DocumentFormat.Epub
    val preview = ready?.preview
    val textOf = when (preview) {
        is FilePreview.Text -> preview
        is FilePreview.Structured -> preview.source
        is FilePreview.Html -> preview.text
        else -> null
    }
    val bottomInset = if (preview is FilePreview.Pdf || (preview is FilePreview.Structured && preview.document.info.kind == com.xfiles.documents.structure.DocumentKind.Presentation)) 96.dp else 20.dp

    val actions = buildList {
        if (ready != null && !ui.editingMarkup) {
            if (preview is FilePreview.Pdf && preview.searchable) {
                add(ReaderAction(if (ui.showPdfFind) "Close find" else "Find in PDF", EzIcons.Search) {
                    onEvent(ReaderUiEvent.SetPdfFind(!ui.showPdfFind))
                    if (ui.showPdfFind) onClosePdfFind()
                })
            }
            if (preview is FilePreview.Structured || (textOf != null && textOf.content.isNotBlank())) {
                add(ReaderAction("Find in file", EzIcons.Search) { onEvent(ReaderUiEvent.SetSearch(!ui.showSearch)) })
            }
            val rich = when (preview) {
                is FilePreview.Html -> true
                is FilePreview.Text -> fileName.let { it.endsWith(".json", ignoreCase = true) || isSourceCode(it) }
                is FilePreview.Structured -> preview.source != null
                else -> false
            }
            if (rich) {
                add(ReaderAction(if (ui.showSource) "Preview" else "Source", if (ui.showSource) EzIcons.Eye else EzIcons.Code) {
                    onEvent(ReaderUiEvent.SetSource(!ui.showSource))
                })
            }
            if (textOf != null && ready.file.kind == DocumentKind.Text && !textOf.truncated) {
                add(ReaderAction("Edit text", EzIcons.Edit) {
                    val openFile = onEditFile
                    if (openFile != null) openFile(ready.file.uri, ready.file.name) else onEdit(ready.file.name, textOf.content)
                })
            }
            if (preview is FilePreview.Structured && isOfficeEditable(fileName)) {
                add(ReaderAction("Find & replace", EzIcons.Edit) { onEvent(ReaderUiEvent.SetOffice(!ui.showOffice)) })
            }
            if (preview is FilePreview.Pdf) {
                add(ReaderAction("Annotate", EzIcons.Edit) {
                    onEvent(ReaderUiEvent.SetEditingMarkup(true))
                    onClosePdfFind()
                })
                add(ReaderAction("Page grid", EzIcons.Grid) { onEvent(ReaderUiEvent.SetGrid(true)) })
            }
            if (preview is FilePreview.Picture || preview is FilePreview.Pdf) {
                add(ReaderAction("Rotate", EzIcons.Rotate) { documentKey?.let(onRotate) })
            }
            if (paged != null) {
                val next = GallerySort.entries[(gallerySort.ordinal + 1) % GallerySort.entries.size]
                add(ReaderAction("Sort: ${gallerySort.label}", EzIcons.ListLines) { onGallerySort(next) })
            }
            if (preview != FilePreview.Unsupported) {
                add(
                    ReaderAction(if (bookmarked) "Remove bookmark" else "Bookmark", if (bookmarked) EzIcons.Bookmark else EzIcons.BookmarkOutline) {
                        haptics.toggle(!bookmarked)
                        onBookmark()
                    },
                )
            }
            if (preview is FilePreview.Pdf || preview is FilePreview.Structured || (textOf != null && textOf.content.isNotBlank())) {
                add(ReaderAction("At a glance", EzIcons.Lightbulb) { onSummary() })
            }
            if (preview is FilePreview.Text) {
                val percent = (textScale * 100).toInt()
                add(ReaderAction("Larger text ($percent%)", EzIcons.TextT) {
                    val next = (textScale + TEXT_SCALE_STEP).coerceAtMost(TEXT_SCALE_MAX)
                    textScale = next
                    writeTextScale(context, textKind, next)
                })
                add(ReaderAction("Smaller text ($percent%)", EzIcons.TextT) {
                    val next = (textScale - TEXT_SCALE_STEP).coerceAtLeast(TEXT_SCALE_MIN)
                    textScale = next
                    writeTextScale(context, textKind, next)
                })
            }
            add(ReaderAction("Ask Fily", EzIcons.Mic) { onFily() })
            add(ReaderAction("Page style: ${pageStyle.label}", EzIcons.Eye) { onEvent(ReaderUiEvent.SetPageStyleSheet(true)) })
            val printable = ready.file.kind !in setOf(DocumentKind.Audio, DocumentKind.Video, DocumentKind.Unknown)
            if (printable) add(ReaderAction("Print", EzIcons.Print) { onPrint() })
            add(ReaderAction("Share original", EzIcons.Share) { onShare() })
            if (!isInDeviceStorage(ready.file.uri)) {
                add(ReaderAction("Save to Downloads", EzIcons.Share) { saveVm.onEvent(SaveUiEvent.Save(ready.file.uri, ready.file.name)) })
            }
            if (structuredUi.edited) add(ReaderAction("Save edited copy", EzIcons.Share) { saveEdited.launch(fileName) })
        }
    }

    CompositionLocalProvider(LocalViewerScroll provides onAttachScroll, LocalTextScale provides textScale) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = if (inPip) 0.dp else 12.dp, vertical = if (inPip) 0.dp else 8.dp)) {
                if (!inPip && !epub) {
                    val galleryCounter = paged?.let { "  ${it.index + 1}/${it.size}" }.orEmpty()
                    ReaderTopBar(
                        title = fileName.ifEmpty { "Reader" } + galleryCounter,
                        backEnabled = !markup.saving,
                        onBack = closeReader,
                    )
                }
                if (!inPip) SensitiveFileHint(documentKey, sensitive, Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
                if (!inPip && ui.showOffice && ready != null && isOfficeEditable(ready.file.name)) {
                    OfficeFindReplacePanel(
                        uri = ready.file.uri,
                        name = ready.file.name,
                        onPreview = onEditedPreview,
                        onClose = { onEvent(ReaderUiEvent.SetOffice(false)) },
                    )
                }
                if (!inPip && ui.editingMarkup && ready?.preview is FilePreview.Pdf) {
                    PdfMarkupControls(
                        mode = ui.markupMode,
                        state = markup,
                        onMode = { onEvent(ReaderUiEvent.SetMarkupMode(it)) },
                        onUndo = onUndoMarkup,
                        onPrepare = onPrepareMarkup,
                        onSave = { saveAnnotated.launch("${ready.file.name.substringBeforeLast('.')}-annotated.pdf") },
                    )
                }
                if (!inPip && ui.showPdfFind && ready?.preview is FilePreview.Pdf) {
                    PdfFindBar(pdfFind, onPdfQuery, onPdfMatch, onClose = {
                        onEvent(ReaderUiEvent.SetPdfFind(false))
                        onClosePdfFind()
                    })
                }
                ReaderBody(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .chromeGestures(
                            onScrollDown = { onEvent(ReaderUiEvent.SetActionsVisible(false)) },
                            onScrollUp = { onEvent(ReaderUiEvent.SetActionsVisible(true)) },
                            onTap = { onEvent(ReaderUiEvent.ToggleActionsVisible) },
                        ),
                    state = state,
                    ui = ui,
                    onEvent = onEvent,
                    pageStyle = pageStyle,
                    gallery = gallery,
                    paged = paged,
                    rotation = rotation,
                    rotationOf = rotationOf,
                    markup = markup,
                    pdfFind = pdfFind,
                    structuredEdits = structuredEdits,
                    onBack = onBack,
                    onClose = closeReader,
                    onFily = onFily,
                    onPassword = onPassword,
                    onPage = onPage,
                    onPdfQuery = onPdfQuery,
                    onAddMarkup = onAddMarkup,
                    pdfThumbnail = pdfThumbnail,
                    pdfText = pdfText,
                    pagePreview = pagePreview,
                    onOpenArchiveEntry = onOpenArchiveEntry,
                    onGalleryPage = onGalleryPage,
                    galleryPage = galleryPage,
                    galleryAnimation = galleryAnimation,
                    pictureAnimation = pictureAnimation,
                )
                if (ready?.bookmarkError == true) {
                    Text("This file provider does not allow Xfiles to keep access for bookmarks.", color = MaterialTheme.colorScheme.error)
                }
                val pdf = ready?.preview as? FilePreview.Pdf
                if (ready != null && pdf != null) {
                    if (ready.turningPage) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (ready.pageError) Text("This page could not be opened.", color = MaterialTheme.colorScheme.error)
                    if (!ui.editingMarkup) PdfToolbar(
                        page = pdf.page,
                        pageCount = pdf.pageCount,
                        zoom = ui.pdfZoom,
                        twoUp = ui.pdfTwoUp,
                        grid = ui.showGrid,
                        busy = ready.turningPage,
                        thumbnail = pdfThumbnail,
                        onPage = onPage,
                        onJump = { onEvent(ReaderUiEvent.SetJump(true)) },
                        onZoom = { onEvent(ReaderUiEvent.SetPdfZoom(it)) },
                        onTwoUp = { onEvent(ReaderUiEvent.SetPdfTwoUp(it)) },
                        onGrid = { onEvent(ReaderUiEvent.SetGrid(it)) },
                    )
                    if (ui.showJump) {
                        PdfJumpDialog(pdf.page, pdf.pageCount, onJump = onPage, onDismiss = { onEvent(ReaderUiEvent.SetJump(false)) })
                    }
                }
                if (saveState.saving) {
                    saveState.progress?.let { fraction ->
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.CenterHorizontally))
            }
            if (ready != null && !inPip && !epub) {
                // Hidden while reading, the button comes back by itself once the content has been still for a moment.
                LaunchedEffect(ui.actionsVisible) {
                    if (!ui.actionsVisible) {
                        delay(ACTIONS_RETURN_MS)
                        onEvent(ReaderUiEvent.SetActionsVisible(true))
                    }
                }
                if (ui.actionsOpen) ReaderActionScrim(onClose = { onEvent(ReaderUiEvent.SetActionsOpen(false)) })
                AnimatedVisibility(
                    visible = ui.actionsVisible || ui.actionsOpen,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    ReaderActionButton(
                        actions = actions,
                        open = ui.actionsOpen,
                        onOpenChange = { onEvent(ReaderUiEvent.SetActionsOpen(it)) },
                        modifier = Modifier.padding(end = 16.dp, bottom = bottomInset),
                    )
                }
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
                    onEvent(ReaderUiEvent.SetPdfFind(true))
                    onPdfQuery(query)
                } else {
                    copyToClipboard(context, query)
                }
            },
            onDismiss = onDismissSummary,
        )
    }
    if (ui.showPageStyle) {
        PageStyleSheet(
            current = pageStyle,
            onPick = { style ->
                pageStyle = style
                writePageStyle(context, pageStyleKey(fileName), style)
                onEvent(ReaderUiEvent.SetPageStyleSheet(false))
            },
            onDismiss = { onEvent(ReaderUiEvent.SetPageStyleSheet(false)) },
        )
    }
    if (ui.confirmDiscard) {
        AlertDialog(
            onDismissRequest = { onEvent(ReaderUiEvent.SetConfirmDiscard(false)) },
            title = { Text("Discard unsaved annotations?") },
            dismissButton = { TextButton(onClick = { onEvent(ReaderUiEvent.SetConfirmDiscard(false)) }) { Text("Keep editing") } },
            confirmButton = { TextButton(onClick = onBack) { Text("Discard") } },
        )
    }
}

/** How long the hidden action button stays away before it returns. */
private const val ACTIONS_RETURN_MS = 2_500L
