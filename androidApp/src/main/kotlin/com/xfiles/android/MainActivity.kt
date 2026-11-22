package com.xfiles.android

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.zIndex
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.preferredFrameRate
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.crash.CrashBreadcrumbs
import com.xfiles.crash.CrashReports
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PrintRequest
import com.xfiles.core.documents.launchSystemPrint
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.xfiles.design.EzTheme
import com.xfiles.design.LocalHapticsEnabled
import com.xfiles.design.LocalReducedMotion
import com.xfiles.design.ReducedMotionMonitor
import com.xfiles.design.glassScreen
import com.xfiles.design.libraryBackground
import com.xfiles.design.readerBackground
import com.xfiles.feature.assistant.FilyViewModel
import com.xfiles.feature.assistant.VoiceAssistantSheet
import com.xfiles.feature.assistant.speechLanguageLabel
import com.xfiles.feature.editor.TextEditorScreen
import com.xfiles.feature.editor.TextEditorViewModel
import com.xfiles.feature.library.AboutScreen
import com.xfiles.feature.library.LibraryScreen
import com.xfiles.feature.library.LibraryViewModel
import com.xfiles.feature.reader.DocumentAnalysisViewModel
import com.xfiles.feature.reader.LocalMediaPip
import com.xfiles.feature.reader.MediaPip
import com.xfiles.feature.reader.ReaderScreen
import com.xfiles.feature.reader.ReaderState
import com.xfiles.feature.tools.ArchiveMode
import com.xfiles.feature.tools.ArchiveToolScreen
import com.xfiles.feature.tools.PdfToolsScreen
import com.xfiles.feature.tools.PdfToolsViewModel
import com.xfiles.feature.tools.ConvertViewModel
import com.xfiles.feature.tools.ConvertTool
import com.xfiles.feature.tools.OpenTool
import com.xfiles.feature.library.LibraryTool
import com.xfiles.android.widget.WidgetActions
import com.xfiles.tidy.TidyService
import com.xfiles.feature.tidy.TidyUpScreen
import com.xfiles.feature.tidy.TidyViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xfiles.fily.ListedFile
import com.xfiles.fily.ReaderControl
import com.xfiles.settings.ThemePreference
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject internal lateinit var appearance: AppearanceStore
    @Inject lateinit var documents: DocumentRepository
    @Inject lateinit var crashReports: CrashReports
    @Inject lateinit var cloudCrash: CrashReportingSetup
    private val library by viewModels<LibraryViewModel>()
    private val analysis by viewModels<DocumentAnalysisViewModel>()
    private val voice by viewModels<FilyViewModel>()
    private val editor by viewModels<TextEditorViewModel>()
    private val pdfTools by viewModels<PdfToolsViewModel>()
    private val convert by viewModels<ConvertViewModel>()
    @Inject lateinit var tidy: TidyService
    @Inject lateinit var librarySearch: com.xfiles.core.documents.LibrarySearchRepository
    @Inject lateinit var sharing: com.xfiles.core.documents.DocumentSharing
    @Inject lateinit var filySource: com.xfiles.android.appfunctions.FilyDocumentsSource
    private val pendingOpen = MutableStateFlow<Uri?>(null)
    /** A home-screen widget button or app shortcut: [WidgetActions.SCAN], [WidgetActions.FILY], [WidgetActions.OPEN] or [WidgetActions.TOOLS]. */
    private val pendingAction = MutableStateFlow<String?>(null)
    /** A note an AppFunction drafted (title to text), opened in the editor for the user to review and save. */
    private val pendingNote = MutableStateFlow<Pair<String, String>?>(null)
    /** Plain text or a link shared from another app (ACTION_SEND with EXTRA_TEXT). */
    private val pendingSharedText = MutableStateFlow<String?>(null)
    /** The system's motion preference (Remove animations / animator scale), feeding LocalReducedMotion. */
    private val motion by lazy { ReducedMotionMonitor(contentResolver) }
    /** Picture-in-Picture state for the player; provided to the UI through [LocalMediaPip]. */
    private val mediaPip = MediaPip()
    private val openPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { pendingOpen.value = it } }

    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash stays until the first library listing is ready and a minimum time has passed (StartupGate).
        val systemSplash = installSplashScreen()
        super.onCreate(savedInstanceState)
        systemSplash.setKeepOnScreenCondition { StartupGate.keepSplash() }
        // The branded intro plays on a cold start only; rotation and warm starts skip it.
        val showIntro = savedInstanceState == null && StartupGate.claimIntro()
        enableEdgeToEdge()
        if (savedInstanceState == null) captureOpenRequest(intent)
        setContent {
            val theme by appearance.theme.collectAsStateWithLifecycle()
            val haptics by appearance.haptics.collectAsStateWithLifecycle()
            val darkTheme = when (theme) {
                ThemePreference.System -> isSystemInDarkTheme()
                ThemePreference.Dark -> true
                ThemePreference.Light -> false
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            var introVisible by rememberSaveable { mutableStateOf(showIntro) }
            val stack = rememberNavBackStack(Route.Library)
            val navigator = remember(stack) { AppNavigator(stack, analysis, editor) }
            val draft by editor.state.collectAsStateWithLifecycle()
            val reader by analysis.state.collectAsStateWithLifecycle()
            SideEffect(reader != null, draft != null) { navigator.sync(reader != null, draft != null) }

            val openRequest by pendingOpen.collectAsStateWithLifecycle()
            LaunchedEffect(openRequest) {
                val uri = openRequest ?: return@LaunchedEffect
                pendingOpen.compareAndSet(uri, null)
                navigator.openDocument(uri)
            }
            var displayedFiles by remember { mutableStateOf(emptyList<FileEntry>()) }
            var showFily by rememberSaveable { mutableStateOf(false) }
            val action by pendingAction.collectAsStateWithLifecycle()
            LaunchedEffect(action) {
                val requested = action ?: return@LaunchedEffect
                pendingAction.compareAndSet(requested, null)
                when (requested) {
                    WidgetActions.SCAN -> {
                        convert.openScanner()
                        pdfTools.open(com.xfiles.feature.tools.OpenTool.Convert(com.xfiles.feature.tools.ConvertTool.ScanToPdf))
                        navigator.show(Route.Tools)
                    }
                    WidgetActions.FILY -> { showFily = true }
                    WidgetActions.OPEN -> openPicker.launch(arrayOf("*/*"))
                    WidgetActions.TOOLS -> {
                        pdfTools.open(null)
                        navigator.show(Route.Tools)
                    }
                }
            }
            val note by pendingNote.collectAsStateWithLifecycle()
            LaunchedEffect(note) {
                val (title, text) = note ?: return@LaunchedEffect
                pendingNote.compareAndSet(note, null)
                navigator.openEditor("${title.ifBlank { "Note" }.take(MAX_NOTE_NAME)}.txt", text)
            }
            // Shared text: a link is offered in a sheet; any other text opens in the editor, prefilled, to save.
            val sharedText by pendingSharedText.collectAsStateWithLifecycle()
            var sharedLink by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(sharedText) {
                val text = sharedText ?: return@LaunchedEffect
                pendingSharedText.compareAndSet(text, null)
                if (isWebLink(text)) sharedLink = text.trim() else navigator.openEditor("Shared text.txt", text)
            }
            val reducedMotion by motion.reduced.collectAsStateWithLifecycle()

            CompositionLocalProvider(LocalHapticsEnabled provides haptics, LocalReducedMotion provides reducedMotion, LocalMediaPip provides mediaPip) {
                EzTheme(darkTheme = darkTheme) {
                    sharedLink?.let { link ->
                        SharedLinkSheet(
                            link = link,
                            onSaveNote = { navigator.openEditor("Shared link.txt", link); sharedLink = null },
                            onDismiss = { sharedLink = null },
                        )
                    }
                    val onLibrary = navigator.top in setOf(Route.Library, Route.About, Route.Tools, Route.Tidy, Route.CompressZip, Route.ExtractArchive)
                    val background = if (onLibrary) Modifier.libraryBackground() else Modifier.readerBackground()
                    val inPip by mediaPip.isInPictureInPicture.collectAsStateWithLifecycle()
                    // Picture-in-Picture: a plain black edge-to-edge window, no glass or inset padding.
                    // The media player draws edge to edge on black and keeps its controls inside the safe area itself.
                    val readerState by analysis.state.collectAsStateWithLifecycle()
                    val mediaOnTop = navigator.top == Route.Reader && (readerState as? ReaderState.Ready)?.preview is FilePreview.Media
                    val chrome = if (inPip || mediaOnTop) Modifier.background(Color.Black) else Modifier.then(background).glassScreen().safeDrawingPadding()
                    // Vote for the display's top refresh rate while Xfiles is on screen (120/144 Hz panels).
                    Box(Modifier.fillMaxSize().preferredFrameRate(FrameRateCategory.High).then(chrome)) {
                        if (introVisible) SplashIntro(Modifier.zIndex(1f)) { introVisible = false }
                        NavDisplay(
                            backStack = stack,
                            onBack = navigator::back,
                            entryProvider = entryProvider {
                                entry<Route.Library> {
                                    LibraryRoute(navigator, onFily = { showFily = true }, onListedFiles = { displayedFiles = it; StartupGate.markDataReady() })
                                }
                                entry<Route.Reader> { ReaderRoute(navigator, onFily = { showFily = true }) }
                                entry<Route.Editor> { draft?.let { TextEditorScreen(it, editor) } }
                                entry<Route.Tools> {
                                    PdfToolsScreen(pdfTools, onBack = navigator::back)
                                }
                                entry<Route.CompressZip> {
                                    ArchiveToolScreen(mode = ArchiveMode.Compress, onBack = navigator::back)
                                }
                                entry<Route.ExtractArchive> {
                                    ArchiveToolScreen(mode = ArchiveMode.Extract, onBack = navigator::back)
                                }
                                entry<Route.Tidy> {
                                    TidyUpScreen(
                                        viewModel = hiltViewModel<TidyViewModel>(),
                                        onBack = navigator::back,
                                        onOpen = { navigator.openDocument(Uri.parse(it.uri)) },
                                    )
                                }
                                entry<Route.About> {
                                    AboutScreen(
                                        onBack = navigator::back,
                                        theme = theme,
                                        haptics = haptics,
                                        onTheme = appearance::setTheme,
                                        onHaptics = appearance::setHaptics,
                                        cloudCrash = cloudCrash,
                                        fily = rememberFilyAbout(),
                                        onClearSearchIndex = { librarySearch.clear() },
                                    )
                                }
                            },
                        )
                        if (showFily && !inPip) FilyOverlay(navigator, { displayedFiles }, onDismiss = { showFily = false })
                        if (!inPip) CrashReportPrompt(crashReports)
                        // The screen name only (never a file name), for the crash report.
                        SideEffect(navigator.top) { CrashBreadcrumbs.screen = (navigator.top as? Route)?.toString() }
                    }
                }
            }
        }
    }

    @Composable
    private fun LibraryRoute(navigator: AppNavigator, onFily: () -> Unit, onListedFiles: (List<FileEntry>) -> Unit) {
        val recentFiles by library.recents.collectAsStateWithLifecycle()
        val recentsLoaded by library.recentsLoaded.collectAsStateWithLifecycle()
        // Startup Timing (and Macrobenchmark) ends at the first frame where the library is ready to use.
        ReportDrawnWhen { recentsLoaded }
        val bookmarks by library.bookmarks.collectAsStateWithLifecycle()
        val folderLibrary by library.library.collectAsStateWithLifecycle()
        val contentSearch by library.contentSearch.collectAsStateWithLifecycle()
        val searchEnabled by library.searchEnabled.collectAsStateWithLifecycle()
        val listing by library.listing.collectAsStateWithLifecycle()
        val category by library.category.collectAsStateWithLifecycle()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) navigator.openDocument(uri)
        }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) library.addFolder(tree)
        }
        val context = LocalContext.current
        LaunchedEffect(library) {
            library.prints.collect { request ->
                when (request) {
                    is PrintRequest.Ready -> if (!launchSystemPrint(context, request.pdf, request.name)) {
                        Toast.makeText(context, "No print service is available.", Toast.LENGTH_SHORT).show()
                    }
                    is PrintRequest.Failed -> Toast.makeText(context, request.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        LibraryScreen(
            recentFiles = recentFiles,
            listing = listing,
            category = category,
            onCategory = library::setCategory,
            onQueryChange = library::setQuery,
            bookmarks = bookmarks,
            onBookmark = { navigator.openDocument(it.file.uri, it.page ?: 0) },
            library = folderLibrary,
            searchEnabled = searchEnabled,
            contentSearch = contentSearch,
            onSearchEnabled = library::setSearchEnabled,
            onSearchInside = library::searchInside,
            onStopSearch = library::stopSearch,
            onOpen = { picker.launch(arrayOf("*/*")) },
            onFile = { navigator.openDocument(it.uri) },
            onShareFiles = { files -> sharing.share(files.map { it.uri to it.name }) },
            onPrint = library::print,
            onRemoveRecent = library::removeRecent,
            onClear = library::clearRecents,
            onAddFolder = { folderPicker.launch(null) },
            onRemoveFolder = library::removeFolder,
            onRefresh = library::refresh,
            onAbout = { navigator.show(Route.About) },
            onTools = { tool ->
                fun convertTool(target: ConvertTool) {
                    convert.select(target)
                    pdfTools.open(OpenTool.Convert(target))
                    navigator.show(Route.Tools)
                }
                when (tool) {
                    LibraryTool.PdfTools -> {
                        pdfTools.open(null)
                        navigator.show(Route.Tools)
                    }
                    LibraryTool.ScanToPdf -> {
                        convert.openScanner()
                        convertTool(ConvertTool.ScanToPdf)
                    }
                    LibraryTool.ImageToText -> convertTool(ConvertTool.PictureToText)
                    LibraryTool.ImagesToPdf -> convertTool(ConvertTool.PicturesToPdf)
                    LibraryTool.TextToPdf -> convertTool(ConvertTool.TextToPdf)
                    LibraryTool.NewText -> navigator.openEditor("Note.txt", "")
                    LibraryTool.TidyUp -> navigator.show(Route.Tidy)
                    LibraryTool.CompressZip -> navigator.show(Route.CompressZip)
                    LibraryTool.ExtractArchive -> navigator.show(Route.ExtractArchive)
                }
            },
            onFily = onFily,
            onListedFiles = onListedFiles,
        )
    }

    @Composable
    private fun ReaderRoute(navigator: AppNavigator, onFily: () -> Unit) {
        val state = analysis.state.collectAsStateWithLifecycle().value ?: return
        val pdfFind by analysis.pdfFind.collectAsStateWithLifecycle()
        val markup by analysis.markup.collectAsStateWithLifecycle()
        val printing by analysis.printing.collectAsStateWithLifecycle()
        val sensitive by analysis.sensitive.collectAsStateWithLifecycle()
        val gallery by analysis.galleryState.collectAsStateWithLifecycle()
        val gallerySort by analysis.gallerySort.collectAsStateWithLifecycle()
        val bookmarks by library.bookmarks.collectAsStateWithLifecycle()
        val ready = state as? ReaderState.Ready
        val page = (ready?.preview as? FilePreview.Pdf)?.page
        val bookmarked = ready != null && bookmarks.any { it.file.uri == ready.file.uri && it.page == page }
        ReaderScreen(
            state = state,
            bookmarked = bookmarked,
            onBookmark = analysis::toggleBookmark,
            onEdit = navigator::openEditor,
            onEditFile = navigator::openEditorFile,
            structuredEdits = analysis.structuredEdits,
            onBack = navigator::back,
            onPage = analysis::changePage,
            onSummary = analysis::showSummary,
            onDismissSummary = analysis::dismissSummary,
            onPassword = analysis::unlock,
            onOpenArchiveEntry = analysis::openNested,
            onNestedBack = analysis::popNested,
            pdfFind = pdfFind,
            onPdfQuery = analysis::findPdf,
            onPdfMatch = analysis::movePdfMatch,
            onClosePdfFind = analysis::clearPdfSearch,
            markup = markup,
            onAddMarkup = analysis::addMarkup,
            onUndoMarkup = analysis::undoMarkup,
            onPrepareMarkup = analysis::prepareMarkup,
            onSaveMarkup = analysis::saveMarkup,
            onFily = onFily,
            printing = printing,
            printRequests = analysis.printRequests,
            onPrint = analysis::print,
            onDismissPrintError = analysis::dismissPrintError,
            sensitive = sensitive,
            onShare = analysis::share,
            onTwoPage = analysis::setTwoPage,
            pdfThumbnail = analysis::pdfThumbnail,
            pdfText = analysis::pdfText,
            gallery = gallery,
            onGalleryPage = analysis::selectGalleryPage,
            galleryPage = analysis::galleryPage,
            galleryAnimation = analysis::galleryAnimation,
            pictureAnimation = analysis::pictureAnimation,
            gallerySort = gallerySort,
            onGallerySort = analysis::setGallerySort,
            rotationOf = analysis::rotationOf,
            onRotate = analysis::rotate,
            onEditedPreview = analysis::showEditedPreview,
        )
    }

    @Composable
    private fun FilyOverlay(navigator: AppNavigator, listed: () -> List<FileEntry>, onDismiss: () -> Unit) {
        val reader by analysis.state.collectAsStateWithLifecycle()
        val active = reader as? ReaderState.Ready
        val control = remember { ReaderControl() }
        val local = remember { FilyLibrary.create(filySource, analysis) }
        // Rescans the linked folders and reloads recents each time Fily opens, so its files are current.
        LaunchedEffect(Unit) { filySource.refresh() }
        val session = remember {
            val actions = FilyActions(navigator, local, analysis, pdfTools, convert, tidy, sharing)
            voice.createSession(
                documents = local,
                reader = control,
                fileNames = { local.files().map(FileEntry::name) },
                navigator = actions,
                tidy = actions,
            ).apply {
                listedFiles = { listed().map { ListedFile(it.uri.toString(), it.name) } }
                closeDocument = {
                    if (analysis.markup.value.marks.isEmpty() && navigator.top == Route.Reader) navigator.back()
                }
            }
        }
        val activeFile = active?.file?.uri
        val pdf = active?.preview is FilePreview.Pdf
        SideEffect { session.openDocumentId = activeFile?.toString() }
        DisposableEffect(control, activeFile, pdf) {
            val unbind = if (pdf) control.attach(FilyPdfTarget(analysis)) else null
            onDispose { unbind?.invoke() }
        }
        val install by voice.install.collectAsStateWithLifecycle()
        val enabled by voice.access.enabled.collectAsStateWithLifecycle()
        // Fily needs its model on the phone: until then, offer the one-time download instead of the overlay.
        if (enabled && install != com.xfiles.fily.ModelInstall.Installed) {
            com.xfiles.feature.library.GetFilyDialog(
                install = install,
                bytes = com.xfiles.fily.AssistantModel.Fily.bytes,
                onDownload = voice::download,
                onImport = voice::import,
                onCancel = voice::cancelDownload,
                onDismiss = onDismiss,
            )
        } else {
            VoiceAssistantSheet(voice, session, onDismiss = onDismiss)
        }
    }

    /** The About screen's Fily settings: the same switches and model as the Fily overlay. */
    @Composable
    private fun rememberFilyAbout(): com.xfiles.feature.library.FilyAbout {
        val enabled by voice.access.enabled.collectAsStateWithLifecycle()
        val contents by voice.access.contentAccess.collectAsStateWithLifecycle()
        val install by voice.install.collectAsStateWithLifecycle()
        val support by voice.speechLanguages.collectAsStateWithLifecycle()
        val picked by voice.speechLanguage.collectAsStateWithLifecycle()
        return com.xfiles.feature.library.FilyAbout(
            enabled = enabled,
            contentAccess = contents,
            onEnabled = voice::setEnabled,
            onContentAccess = voice::setContentAccess,
            model = install,
            onDownload = voice::download,
            onImport = voice::import,
            onCancelDownload = voice::cancelDownload,
            onRemoveModel = voice::deleteModel,
            // The recognizer's language list exists from Android 13; older phones keep the phone's English.
            languages = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null else com.xfiles.feature.library.FilyLanguages(
                support = support,
                selected = picked,
                label = ::speechLanguageLabel,
                onSelect = { tag ->
                    voice.selectSpeechLanguage(tag)
                    if (support.downloadable.any { it.tag == tag }) voice.downloadSpeechLanguage(tag)
                },
                onRefresh = voice::refreshSpeechLanguages,
            ),
        )
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        mediaPip.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        mediaPip.onUserLeaveHint(this)
    }

    override fun onResume() {
        super.onResume()
        preferHighestRefreshRate()
    }

    override fun onStart() {
        super.onStart()
        motion.start()
    }

    override fun onStop() {
        motion.stop()
        super.onStop()
    }

    /**
     * Asks for the fastest display mode at the current resolution. Many phones (Xiaomi, Samsung) keep
     * apps at 60 Hz unless the window asks; this lets scrolling, paging and video run at 90/120/144 Hz.
     */
    private fun preferHighestRefreshRate() {
        val display = display ?: return
        val current = display.mode
        val fastest = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (window.attributes.preferredDisplayModeId != fastest.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureOpenRequest(intent)
    }

    private fun captureOpenRequest(intent: Intent) {
        // The shared files are content:// URIs with a temporary read grant, so they open exactly like VIEW.
        fun sharedFiles(): List<Uri> {
            val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            return uris.orEmpty().filter { it.scheme == "content" }
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> pendingOpen.value = intent.data?.takeIf { it.scheme == "content" }
            // Share to Xfiles: the first file opens; the rest go to Recent so they stay one tap away.
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                if (text != null && sharedFiles().isEmpty()) pendingSharedText.value = text
                else pendingOpen.value = sharedFiles().firstOrNull()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val files = sharedFiles()
                pendingOpen.value = files.firstOrNull()
                library.receiveShared(files.drop(1))
            }
        }
        intent.getStringExtra(WidgetActions.EXTRA)?.let { pendingAction.value = it }
        if (intent.getStringExtra(WidgetActions.EXTRA) == WidgetActions.NOTE) {
            pendingNote.value = intent.getStringExtra(WidgetActions.EXTRA_NOTE_TITLE).orEmpty() to
                intent.getStringExtra(WidgetActions.EXTRA_NOTE_TEXT).orEmpty()
        }
    }

    private companion object {
        const val MAX_NOTE_NAME = 60
    }
}
