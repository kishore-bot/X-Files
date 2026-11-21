package com.xfiles.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.xfiles.design.EzTheme
import com.xfiles.design.LocalHapticsEnabled
import com.xfiles.design.glassScreen
import com.xfiles.design.libraryBackground
import com.xfiles.design.readerBackground
import com.xfiles.feature.assistant.FilyViewModel
import com.xfiles.feature.assistant.VoiceAssistantSheet
import com.xfiles.feature.editor.TextEditorScreen
import com.xfiles.feature.editor.TextEditorViewModel
import com.xfiles.feature.library.AboutScreen
import com.xfiles.feature.library.LibraryScreen
import com.xfiles.feature.library.LibraryViewModel
import com.xfiles.feature.reader.DocumentAnalysisViewModel
import com.xfiles.feature.reader.ReaderScreen
import com.xfiles.feature.reader.ReaderState
import com.xfiles.feature.tools.PdfToolsScreen
import com.xfiles.feature.tools.PdfToolsViewModel
import com.xfiles.feature.tools.ConvertViewModel
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
    private val pendingOpen = MutableStateFlow<Uri?>(null)
    /** A home-screen widget button: [WidgetActions.SCAN], [WidgetActions.FILY] or [WidgetActions.OPEN]. */
    private val pendingAction = MutableStateFlow<String?>(null)
    private val openPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { pendingOpen.value = it } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val stack = rememberNavBackStack(Route.Library)
            val navigator = remember(stack) { AppNavigator(stack, analysis, editor) }
            val draft by editor.state.collectAsStateWithLifecycle()
            val reader by analysis.state.collectAsStateWithLifecycle()
            LaunchedEffect(reader != null, draft != null) { navigator.sync(reader != null, draft != null) }

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
                        pdfTools.showConvert(true)
                        navigator.show(Route.Tools)
                    }
                    WidgetActions.FILY -> { showFily = true }
                    WidgetActions.OPEN -> openPicker.launch(arrayOf("*/*"))
                }
            }

            CompositionLocalProvider(LocalHapticsEnabled provides haptics) {
                EzTheme(darkTheme = darkTheme) {
                    val onLibrary = navigator.top in setOf(Route.Library, Route.About, Route.Tools, Route.Tidy)
                    val background = if (onLibrary) Modifier.libraryBackground() else Modifier.readerBackground()
                    Box(Modifier.fillMaxSize().then(background).glassScreen().safeDrawingPadding()) {
                        NavDisplay(
                            backStack = stack,
                            onBack = navigator::back,
                            entryProvider = entryProvider {
                                entry<Route.Library> {
                                    LibraryRoute(navigator, onFily = { showFily = true }, onListedFiles = { displayedFiles = it })
                                }
                                entry<Route.Reader> { ReaderRoute(navigator, onFily = { showFily = true }) }
                                entry<Route.Editor> { draft?.let { TextEditorScreen(it, editor) } }
                                entry<Route.Tools> {
                                    PdfToolsScreen(
                                        pdfTools,
                                        onBack = navigator::back,
                                        onNewNote = { navigator.openEditor("Note.txt", "") },
                                        onTidy = { navigator.show(Route.Tidy) },
                                    )
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
                                    )
                                }
                            },
                        )
                        if (showFily) FilyOverlay(navigator, { displayedFiles }, onDismiss = { showFily = false })
                        CrashReportPrompt(crashReports)
                        // The screen name only (never a file name), for the crash report.
                        LaunchedEffect(navigator.top) { CrashBreadcrumbs.screen = (navigator.top as? Route)?.toString() }
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
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) navigator.openDocument(uri)
        }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) library.addFolder(tree)
        }
        LibraryScreen(
            recentFiles = recentFiles,
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
            onClear = library::clearRecents,
            onAddFolder = { folderPicker.launch(null) },
            onRemoveFolder = library::removeFolder,
            onRefresh = library::refresh,
            onAbout = { navigator.show(Route.About) },
            onTools = { navigator.show(Route.Tools) },
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
        val bookmarks by library.bookmarks.collectAsStateWithLifecycle()
        val ready = state as? ReaderState.Ready
        val page = (ready?.preview as? FilePreview.Pdf)?.page
        val bookmarked = ready != null && bookmarks.any { it.file.uri == ready.file.uri && it.page == page }
        ReaderScreen(
            state = state,
            bookmarked = bookmarked,
            onBookmark = analysis::toggleBookmark,
            onEdit = navigator::openEditor,
            onBack = navigator::back,
            onPage = analysis::changePage,
            onSummary = analysis::showSummary,
            onDismissSummary = analysis::dismissSummary,
            onPassword = analysis::unlock,
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
        )
    }

    @Composable
    private fun FilyOverlay(navigator: AppNavigator, listed: () -> List<FileEntry>, onDismiss: () -> Unit) {
        val reader by analysis.state.collectAsStateWithLifecycle()
        val active = reader as? ReaderState.Ready
        val control = remember { ReaderControl() }
        val local = remember { FilyLibrary(library, analysis, documents, librarySearch) }
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
        VoiceAssistantSheet(voice, session, onDismiss = onDismiss)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureOpenRequest(intent)
    }

    private fun captureOpenRequest(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            pendingOpen.value = intent.data?.takeIf { it.scheme == "content" }
        }
        intent.getStringExtra(WidgetActions.EXTRA)?.let { pendingAction.value = it }
    }
}
