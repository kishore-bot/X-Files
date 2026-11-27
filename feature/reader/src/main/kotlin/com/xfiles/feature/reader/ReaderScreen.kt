package com.xfiles.feature.reader

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
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
import com.xfiles.design.LocalEzDarkTheme
import com.xfiles.design.LocalFilyOpen
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
    onPdfPageSettled: (Int) -> Unit,
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
    onAttachPdfPage: ((String) -> Int?) -> () -> Unit = { {} },
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
    DisposableEffect(chromeVm, onAttachPdfPage) {
        val detach = onAttachPdfPage { key ->
            chromeVm.state.value.takeIf { it.documentKey == key }?.visiblePdfPage
        }
        onDispose { detach() }
    }
    val chromeState by chromeVm.state.collectAsStateWithLifecycle()
    val initialUi = remember(chromeVm, documentKey) { chromeVm.restoreDocument(documentKey) }
    val ui = if (chromeState.documentKey == documentKey) chromeState else initialUi
    val progress = remember(chromeVm, documentKey) { documentKey?.let { ViewerProgress(chromeVm.progress, it) } }
    LaunchedEffect(documentKey) { chromeVm.onEvent(ReaderUiEvent.DocumentChanged(documentKey)) }
    val onEvent: (ReaderUiEvent) -> Unit = chromeVm::onEvent
    val filyOpen = LocalFilyOpen.current
    LaunchedEffect(filyOpen) {
        if (filyOpen) {
            onEvent(ReaderUiEvent.SetActionsOpen(false))
            onEvent(ReaderUiEvent.SetJump(false))
            onEvent(ReaderUiEvent.SetPageStyleSheet(false))
            if (ready?.summary != null) onDismissSummary()
        }
    }

    // Per-file settings fall back to the previous defaults for the file type.
    var pageStyle by rememberSaveable(documentKey) { mutableStateOf(PageStyle.entries.firstOrNull { it.name == progress?.read("style") } ?: readPageStyle(context, pageStyleKey(fileName))) }
    val textKind = if (isSourceCode(fileName) || isSvgName(fileName)) "code" else "text"
    var textScale by rememberSaveable(documentKey, textKind) {
        mutableStateOf(progress?.read("text-scale")?.toFloatOrNull()?.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX) ?: readTextScale(context, textKind))
    }
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
    var awaitingAnnotatedCopy by rememberSaveable(documentKey) { mutableStateOf(false) }
    LaunchedEffect(awaitingAnnotatedCopy, ui.editingMarkup, markup.output, markup.error) {
        if (!awaitingAnnotatedCopy) return@LaunchedEffect
        if (!ui.editingMarkup || markup.error != null) {
            awaitingAnnotatedCopy = false
        } else if (markup.output != null) {
            awaitingAnnotatedCopy = false
            saveAnnotated.launch("${fileName.substringBeforeLast('.')}-annotated.pdf")
        }
    }
    val closeReader: () -> Unit = {
        if (!markup.saving) {
            if (markup.marks.isNotEmpty()) onEvent(ReaderUiEvent.SetConfirmDiscard(true)) else if (!onNestedBack()) onBack()
        }
    }
    BackHandler(enabled = markup.marks.isNotEmpty() || markup.saving, onBack = closeReader)
    BackHandler(enabled = ui.showGrid, onBack = { onEvent(ReaderUiEvent.SetGrid(false)) })
    // Picture-in-Picture shows only the picture: no bars, hints or panels, and no margins.
    val inPip by LocalMediaPip.current.isInPictureInPicture.collectAsStateWithLifecycle()
    // Video and audio take the whole screen, with their own close button, as in EzFiles.
    val media = ready?.preview as? FilePreview.Media
    if (ready != null && media != null) {
        CompositionLocalProvider(LocalViewerProgress provides progress) {
            MediaScreen(ready.file, media.video, onClose = closeReader, onFily = onFily)
        }
        return
    }
    // An EPUB book has its own chrome (with Fily), so the parent reader bar stays hidden.
    val epub = (ready?.preview as? FilePreview.Structured)?.document?.info?.format == DocumentFormat.Epub
    val preview = ready?.preview
    val archive = ready?.file?.name?.let(::isZipName) == true
    val structuredDocument = preview is FilePreview.Structured && !archive
    val textOf = when (preview) {
        is FilePreview.Text -> preview
        is FilePreview.Structured -> preview.source
        is FilePreview.Html -> preview.text
        else -> null
    }

    val actions = buildList {
        if (ready != null && !ui.editingMarkup) {
            if (preview is FilePreview.Pdf && preview.searchable) {
                add(ReaderAction(if (ui.showPdfFind) "Close find" else "Find in PDF", EzIcons.Search) {
                    onEvent(ReaderUiEvent.SetPdfFind(!ui.showPdfFind))
                    if (ui.showPdfFind) onClosePdfFind()
                })
            }
            if (structuredDocument || !textOf?.content.isNullOrBlank()) {
                add(ReaderAction("Find in file", EzIcons.Search) { onEvent(ReaderUiEvent.SetSearch(!ui.showSearch)) })
            }
            val rich = when (preview) {
                is FilePreview.Html -> true
                is FilePreview.Text -> isSvgName(fileName) || fileName.endsWith(".json", ignoreCase = true) || isSourceCode(fileName)
                is FilePreview.Structured -> structuredDocument && preview.source != null
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
            if (structuredDocument && isOfficeEditable(fileName)) {
                add(ReaderAction("Find & replace", EzIcons.Edit) { onEvent(ReaderUiEvent.SetOffice(!ui.showOffice)) })
            }
            if (preview is FilePreview.Pdf) {
                add(ReaderAction("Annotate", EzIcons.Edit) {
                    onEvent(ReaderUiEvent.SetEditingMarkup(true))
                    onClosePdfFind()
                })
                if (preview.pageCount > 1) {
                    add(ReaderAction("Page grid", EzIcons.Grid) { onEvent(ReaderUiEvent.SetGrid(!ui.showGrid)) })
                    add(ReaderAction(if (ui.pdfTwoUp) "Single column" else "Two-page spread", if (ui.pdfTwoUp) EzIcons.SinglePage else EzIcons.TwoPages) {
                        onEvent(ReaderUiEvent.SetGrid(false))
                        onEvent(ReaderUiEvent.SetPdfTwoUp(!ui.pdfTwoUp))
                    })
                }
            }
            if (preview is FilePreview.Picture) {
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
            if (preview is FilePreview.Pdf || structuredDocument || !textOf?.content.isNullOrBlank()) {
                add(ReaderAction("At a glance", EzIcons.Lightbulb) { onSummary() })
            }
            if (preview is FilePreview.Text && (!isSvgName(fileName) || ui.showSource || ui.showSearch)) {
                val percent = (textScale * 100).toInt()
                if (textScale < TEXT_SCALE_MAX) add(ReaderAction("Larger text ($percent%)", EzIcons.TextIncrease) {
                    val next = (textScale + TEXT_SCALE_STEP).coerceAtMost(TEXT_SCALE_MAX)
                    textScale = next
                    progress?.save("text-scale", next.toString())
                })
                if (textScale > TEXT_SCALE_MIN) add(ReaderAction("Smaller text ($percent%)", EzIcons.TextDecrease) {
                    val next = (textScale - TEXT_SCALE_STEP).coerceAtLeast(TEXT_SCALE_MIN)
                    textScale = next
                    progress?.save("text-scale", next.toString())
                })
            }
            if (preview is FilePreview.Pdf || structuredDocument || preview is FilePreview.Text || preview is FilePreview.Html) {
                add(ReaderAction("Page style: ${pageStyle.label}", EzIcons.Theme) { onEvent(ReaderUiEvent.SetPageStyleSheet(true)) })
            }
            val printable = preview != FilePreview.Unsupported && !archive &&
                ready.file.kind !in setOf(DocumentKind.Audio, DocumentKind.Video, DocumentKind.Unknown)
            if (printable) add(ReaderAction("Print", EzIcons.Print) { onPrint() })
            add(ReaderAction("Share original", EzIcons.Share) { onShare() })
            if (!isInDeviceStorage(ready.file.uri)) {
                add(ReaderAction("Save to Downloads", EzIcons.Share) { saveVm.onEvent(SaveUiEvent.Save(ready.file.uri, ready.file.name)) })
            }
            if (structuredDocument && structuredUi.edited) add(ReaderAction("Save edited copy", EzIcons.Share) { saveEdited.launch(fileName) })
        }
    }

    CompositionLocalProvider(
        LocalViewerScroll provides onAttachScroll,
        LocalTextScale provides textScale,
        LocalViewerProgress provides progress,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = if (inPip) 0.dp else 12.dp, vertical = if (inPip) 0.dp else 8.dp)) {
                if (!inPip && !epub) {
                    val galleryCounter = paged?.let { "  ${it.index + 1}/${it.size}" }.orEmpty()
                    ReaderTopBar(
                        title = fileName.ifEmpty { "Reader" } + galleryCounter,
                        backEnabled = !markup.saving,
                        onBack = closeReader,
                        onFily = onFily.takeIf { ready != null && !ui.editingMarkup },
                        actions = actions,
                        settingsOpen = ui.actionsOpen,
                        onSettingsOpen = { onEvent(ReaderUiEvent.SetActionsOpen(it)) },
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
                if (!inPip && ui.showPdfFind && ready?.preview is FilePreview.Pdf) {
                    PdfFindBar(pdfFind, onPdfQuery, onPdfMatch, onClose = {
                        onEvent(ReaderUiEvent.SetPdfFind(false))
                        onClosePdfFind()
                    })
                }
                ReaderBody(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    onPdfPageSettled = onPdfPageSettled,
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
                    if (ready.pageError) Text("This page could not be opened.", color = MaterialTheme.colorScheme.error)
                    if (ui.editingMarkup) {
                        PdfMarkupControls(
                            mode = ui.markupMode,
                            state = markup,
                            onMode = { onEvent(ReaderUiEvent.SetMarkupMode(it)) },
                            onUndo = onUndoMarkup,
                            onSave = {
                                if (markup.output == null) onPrepareMarkup()
                                awaitingAnnotatedCopy = true
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else PdfToolbar(
                        page = (ui.visiblePdfPage ?: pdf.page).coerceIn(0, (pdf.pageCount - 1).coerceAtLeast(0)),
                        pageCount = pdf.pageCount,
                        zoom = ui.pdfZoom,
                        grid = ui.showGrid,
                        colorFilter = pageStyle.pdfFilter(LocalEzDarkTheme.current),
                        thumbnail = pdfThumbnail,
                        onPage = onPage,
                        onJump = { onEvent(ReaderUiEvent.SetJump(true)) },
                        settingsOpen = ui.actionsOpen,
                        onEngage = { onEvent(ReaderUiEvent.SetActionsOpen(false)) },
                        onZoom = { onEvent(ReaderUiEvent.SetPdfZoom(it)) },
                    )
                    if (ui.showJump && !filyOpen) {
                        PdfJumpDialog(ui.visiblePdfPage ?: pdf.page, pdf.pageCount, onJump = onPage, onDismiss = { onEvent(ReaderUiEvent.SetJump(false)) })
                    }
                }
                if (saveState.saving) {
                    saveState.progress?.let { fraction ->
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.CenterHorizontally))
            }

        }
    }
    val summaryState = ready?.summary
    if (ready != null && summaryState != null && !filyOpen) {
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
    if (ui.showPageStyle && !filyOpen) {
        PageStyleSheet(
            current = pageStyle,
            onPick = { style ->
                pageStyle = style
                progress?.save("style", style.name)
                onEvent(ReaderUiEvent.SetPageStyleSheet(false))
            },
            onDismiss = { onEvent(ReaderUiEvent.SetPageStyleSheet(false)) },
        )
    }
    if (ui.confirmDiscard && !filyOpen) {
        AlertDialog(
            onDismissRequest = { onEvent(ReaderUiEvent.SetConfirmDiscard(false)) },
            title = { Text("Discard unsaved annotations?") },
            dismissButton = { TextButton(onClick = { onEvent(ReaderUiEvent.SetConfirmDiscard(false)) }) { Text("Keep editing") } },
            confirmButton = { TextButton(onClick = onBack) { Text("Discard") } },
        )
    }
}
