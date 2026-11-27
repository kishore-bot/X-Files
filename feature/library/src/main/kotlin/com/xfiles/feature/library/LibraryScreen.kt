package com.xfiles.feature.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.core.documents.ArchiveHandoff
import com.xfiles.core.documents.FileBookmark
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FileProgress
import com.xfiles.core.documents.PlaybackProgress
import com.xfiles.core.documents.FolderLibrary
import com.xfiles.core.documents.LibraryContentSearch
import com.xfiles.core.documents.LibrarySearchHit
import com.xfiles.core.documents.LinkedFolder
import com.xfiles.core.documents.canPrint
import com.xfiles.design.BarIconButton
import com.xfiles.design.EzBadgeColors
import com.xfiles.design.EzChip
import com.xfiles.design.EzColors
import com.xfiles.design.EzControlSheet
import com.xfiles.design.EzDialog
import com.xfiles.design.EzEmptyCard
import com.xfiles.design.EzFloatingAction
import com.xfiles.design.EzIcons
import com.xfiles.design.EzNavItem
import com.xfiles.design.EzNavigationBar
import com.xfiles.design.EzSectionPill
import com.xfiles.design.EzShapes
import com.xfiles.design.EzSpacing
import com.xfiles.design.EzTextButton
import com.xfiles.design.FileNameStyle
import com.xfiles.design.FileTypeTile
import com.xfiles.design.FilyMascot
import com.xfiles.design.FilyMascotCallToAction
import com.xfiles.design.GlassIconButton
import com.xfiles.design.LocalFilyOpen
import com.xfiles.design.LocalHapticsEnabled
import com.xfiles.design.EzMotion
import com.xfiles.design.LocalReducedMotion
import com.xfiles.design.PrimaryActionButton
import com.xfiles.design.rememberFrostedBackdrop
import com.xfiles.design.glass
import com.xfiles.design.glassCard
import com.xfiles.design.pressScale
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.library.LibraryCategory
import com.xfiles.documents.library.libraryCategories
import kotlinx.coroutines.launch

/** Most files one share sends: the system share sheet takes a bounded list. */
internal const val MaxSharedFiles = 32

/** Side of a row's preview. */
internal val ThumbnailSize = 56.dp

/** Every filter chip, in display order; the list never changes, so it is built once. */
internal val Categories: List<LibraryCategory> = LibraryCategory.entries

/** Gutter every list item uses; the list itself has no horizontal padding, so rows align with the header. */
internal val RowGutter = Modifier.padding(horizontal = EzSpacing.Gutter)

/** Room the floating Open file button and the tab bar take at the end of the list. */
internal val PortraitClearance = 172.dp

@Composable
fun LibraryScreen(
    recentFiles: List<FileEntry>,
    listing: LibraryListing,
    category: LibraryCategory,
    lastViewed: Map<LibraryCategory, String>,
    playbackRevision: Long,
    playbackFor: (FileEntry) -> PlaybackProgress?,
    onCategory: (LibraryCategory) -> Unit,
    onQueryChange: (String) -> Unit,
    bookmarks: List<FileBookmark>,
    onBookmark: (FileBookmark) -> Unit,
    library: FolderLibrary,
    searchEnabled: Boolean,
    contentSearch: LibraryContentSearch,
    onSearchEnabled: (Boolean) -> Unit,
    onSearchInside: (String) -> Unit,
    onStopSearch: () -> Unit,
    onOpen: () -> Unit,
    onFile: (FileEntry) -> Unit,
    onShareFiles: (List<FileEntry>) -> Unit,
    onPrint: (FileEntry) -> Unit,
    onRemoveRecent: (FileEntry) -> Unit,
    onClear: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (Uri) -> Unit,
    onReconnectFolder: (Uri) -> Unit = {},
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onTools: (LibraryTool) -> Unit,
    onFily: () -> Unit,
    onListedFiles: (List<FileEntry>) -> Unit,
    onSelectedFiles: (List<FileEntry>) -> Unit,
) {
    val search = rememberTextFieldState()
    val query = search.text.toString().trim()
    val context = androidx.compose.ui.platform.LocalContext.current
    val tabPreferences = remember(context) { context.getSharedPreferences("library_view", 0) }
    var tab by rememberSaveable {
        mutableStateOf(HomeTab.entries.firstOrNull { it.name == tabPreferences.getString("tab", null) } ?: HomeTab.Library)
    }
    LaunchedEffect(tab) { tabPreferences.edit().putString("tab", tab.name).apply() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var removingFolder by rememberSaveable { mutableStateOf<String?>(null) }
    // The multi-select set. It is cleared when the tab changes, and by the back gesture.
    val picked = remember { mutableStateListOf<FileEntry>() }
    val selection = picked.toList()
    SideEffect(selection) { onSelectedFiles(selection) }

    LaunchedEffect(onQueryChange) {
        snapshotFlow { search.text.toString().trim() }.collect(onQueryChange)
    }
    LaunchedEffect(tab) { picked.clear() }
    BackHandler(enabled = picked.isNotEmpty()) { picked.clear() }

    val matching = if (tab == HomeTab.Recent) listing.recents else listing.files
    SideEffect(tab, matching, bookmarks) {
        onListedFiles(if (tab == HomeTab.Bookmarks) bookmarks.map { it.file } else matching)
    }
    val searchesInside = searchEnabled && tab == HomeTab.Library && query.length >= 2
    SideEffect(query, searchesInside, library.files) {
        if (searchesInside) onSearchInside(query) else onStopSearch()
    }
    DisposableEffect(Unit) { onDispose(onStopSearch) }
    val window = LocalWindowInfo.current.containerDpSize
    val landscape = window.width >= 480.dp && window.width > window.height
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var foldersOpen by rememberSaveable { mutableStateOf(false) }
    val filyOpen = LocalFilyOpen.current
    LaunchedEffect(filyOpen) {
        if (filyOpen) {
            toolsOpen = false
            foldersOpen = false
            confirmClear = false
            removingFolder = null
        }
    }
    val navItems = remember(tab, toolsOpen) {
        listOf(
            EzNavItem("Library", EzIcons.Folder, tab == HomeTab.Library) { tab = HomeTab.Library },
            EzNavItem("Recent", EzIcons.History, tab == HomeTab.Recent) { tab = HomeTab.Recent },
            EzNavItem("Bookmarks", EzIcons.BookmarkOutline, tab == HomeTab.Bookmarks) { tab = HomeTab.Bookmarks },
            EzNavItem("Tools", EzIcons.Apps, toolsOpen) { toolsOpen = true },
        )
    }
    if (toolsOpen && !filyOpen) {
        ToolsSheet(
            onSelect = {
                toolsOpen = false
                onTools(it)
            },
            onDismiss = { toolsOpen = false },
        )
    }
    if (foldersOpen && !filyOpen) {
        EzControlSheet(onDismiss = { foldersOpen = false }) {
            Text(
                "Linked folders",
                RowGutter.padding(bottom = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            FolderManager(
                folders = library.folders,
                searchEnabled = searchEnabled,
                scanning = library.scanning,
                onAdd = onAddFolder,
                onSearchEnabled = onSearchEnabled,
                onRefresh = onRefresh,
                onRemove = { foldersOpen = false; removingFolder = it },
                onReconnect = onReconnectFolder,
            )
        }
    }
    val haptics = rememberHaptics()
    val hapticsOn = LocalHapticsEnabled.current
    val ezHaptics = rememberHaptics()
    fun setPicked(file: FileEntry, on: Boolean) {
        if (on) {
            if (file !in picked) picked.add(file)
        } else {
            picked.remove(file)
        }
    }
    fun toggle(file: FileEntry) {
        val on = file !in picked
        setPicked(file, on)
        ezHaptics.toggle(on)
    }
    fun longPress(file: FileEntry) {
        if (hapticsOn) haptics.longPress() 
        setPicked(file, file !in picked)
    }
    fun actionsFor(file: FileEntry) = FileRowActions(
        canPrint = file.kind.canPrint(),
        selectable = picked.size < MaxSharedFiles,
        onShare = { onShareFiles(listOf(file)) },
        onPrint = { onPrint(file) },
        onRemove = if (tab == HomeTab.Recent) ({ ezHaptics.confirm(); onRemoveRecent(file) }) else null,
        onCompress = {
            ArchiveHandoff.stageCompress(listOf(file.uri))
            onTools(LibraryTool.CompressZip)
        },
        onExtract = if (file.name.endsWith(".zip", ignoreCase = true)) ({
            ArchiveHandoff.stageExtract(file.uri, file.name)
            onTools(LibraryTool.ExtractArchive)
        }) else null,
    )
    fun openPicked() {
        val file = picked.single()
        picked.clear()
        onFile(file)
    }
    val noFolders = library.folders.isEmpty()
    // The list scrolls under the floating button and the tab bar, and clears them at the end.
    var chromeHeight by remember { mutableStateOf(0) }
    val bottomClearance = if (landscape) 24.dp else with(LocalDensity.current) {
        if (chromeHeight > 0) chromeHeight.toDp() + 12.dp else PortraitClearance
    }
    val startClearance = if (landscape) 112.dp else 0.dp
    val showsAssistant = tab == HomeTab.Library && (query.isNotEmpty() || !noFolders)

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalReducedMotion.current
    val fileCategory = listing.category
    val section = if (tab == HomeTab.Library) fileCategory.name else tab.name
    var previousSection by rememberSaveable { mutableStateOf(section) }
    var browseVisible by rememberSaveable { mutableStateOf(true) }
    var searchFocused by remember { mutableStateOf(false) }
    val searchRevealDistance = with(LocalDensity.current) { 24.dp.toPx() }
    val revealOnScroll = remember(listState, searchRevealDistance) {
        object : NestedScrollConnection {
            private var travel = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                if ((travel < 0f) != (available.y < 0f)) travel = 0f
                travel += available.y
                if (travel >= searchRevealDistance) {
                    browseVisible = true
                    travel = 0f
                } else if (travel <= -searchRevealDistance && listState.canScrollBackward && listState.canScrollForward) {
                    browseVisible = false
                    travel = 0f
                }
                return Offset.Zero
            }
        }
    }
    val deepScroll by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex >= 8 } }
    val atTop by remember(listState) { derivedStateOf { !listState.canScrollBackward } }
    val topVisible = deepScroll && picked.isEmpty() && !searchFocused
    val backToTop: () -> Unit = {
        browseVisible = true
        scope.launch {
            if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
        }
    }
    val lastViewedUri = when (tab) {
        HomeTab.Library -> lastViewed[fileCategory] ?: recentFiles.firstOrNull { entry ->
            fileCategory == LibraryCategory.All || fileCategory in libraryCategories(entry.name)
        }?.uri?.toString()
        HomeTab.Recent -> lastViewed[LibraryCategory.All] ?: recentFiles.firstOrNull()?.uri?.toString()
        HomeTab.Bookmarks -> lastViewed[LibraryCategory.All]
    }
    val historyId = remember(lastViewedUri) { lastViewedUri?.let { FileProgress.identity(it) } }
    val markedIndex = remember(historyId, matching) {
        if (historyId == null) -1 else matching.indexOfFirst { FileProgress.identity(it.uri.toString()) == historyId }
    }
    val markedUri = matching.getOrNull(markedIndex)?.uri
    val imageTab = tab == HomeTab.Library && fileCategory == LibraryCategory.Images
    val showFolderEmpty = tab == HomeTab.Library && noFolders && !library.scanning && matching.isEmpty() && query.isEmpty()
    val showError = tab == HomeTab.Library && library.error != null
    val showLimit = tab == HomeTab.Library && library.reachedLimit
    val imageStart = 1 + (if (showError) 1 else 0) + (if (showLimit) 1 else 0)
    var restoredImage by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(section, imageTab, markedUri, query.isEmpty(), imageStart) {
        if (previousSection != section) {
            previousSection = section
            browseVisible = true
            if (tab == HomeTab.Bookmarks) searchFocused = false
            restoredImage = null
            if (!imageTab || query.isNotEmpty() || markedUri == null) listState.scrollToItem(0)
        }
        if (imageTab && query.isEmpty() && markedUri != null && restoredImage != markedUri) {
            listState.scrollToItem(imageStart + markedIndex)
            restoredImage = markedUri
        }
    }
    val showingContentSearch = searchesInside && contentSearch.query == query
    val visibleHits = remember(contentSearch.hits, fileCategory) {
        contentSearch.hits.filter { fileCategory == LibraryCategory.All || fileCategory in libraryCategories(it.file.name) }
    }
    val backdrop = rememberFrostedBackdrop()
    Column(Modifier.fillMaxSize().imePadding()) {
        if (picked.isNotEmpty()) {
            SelectionBar(
                count = picked.size,
                onOpen = if (picked.size == 1) ({ openPicked() }) else null,
                onShare = { onShareFiles(picked.toList()) },
                onClear = { picked.clear() },
            )
        } else {
            Header(
                onSettings = onSettings.takeUnless { landscape },
                onVoice = onFily,
                compact = landscape,
                onFolders = { foldersOpen = true }.takeIf { tab == HomeTab.Library },
            )
        }
        if (tab != HomeTab.Bookmarks) {
            AnimatedVisibility(
                visible = browseVisible || atTop || searchFocused,
                modifier = Modifier.fillMaxWidth().padding(start = startClearance),
                enter = EzMotion.expandFromTop(reducedMotion),
                exit = EzMotion.collapseToTop(reducedMotion),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    LibrarySearch(
                        search, compact = landscape, modifier = RowGutter.padding(vertical = 4.dp),
                        onFocus = { searchFocused = it },
                    )
                    if (tab == HomeTab.Library) FilterRow(category, onCategory, compact = landscape)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = backdrop.source(
                    Modifier.fillMaxSize().padding(start = startClearance).nestedScroll(revealOnScroll),
                ),
                contentPadding = PaddingValues(top = if (landscape) 4.dp else 12.dp, bottom = bottomClearance),
                verticalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 8.dp),
            ) {
                if (tab == HomeTab.Library || tab == HomeTab.Recent) {
                    item(key = "list-title", contentType = "section") {
                        val title = when {
                            tab == HomeTab.Recent -> "Recent files"
                            query.isNotEmpty() -> "File names"
                            fileCategory == LibraryCategory.All -> "All files"
                            else -> fileCategory.label
                        }
                        SectionTitle(
                            title = title,
                            count = if (matching.isEmpty()) null else matching.size.toString(),
                            busy = library.scanning,
                            onClear = { confirmClear = true }.takeIf { tab == HomeTab.Recent && recentFiles.isNotEmpty() },
                            compact = landscape,
                        )
                    }
                }
                if (tab == HomeTab.Bookmarks) item(key = "bookmarks-title", contentType = "section") {
                    SectionTitle("Bookmarks", count = if (bookmarks.isEmpty()) null else bookmarks.size.toString(), compact = landscape)
                }
                if (showError) item(key = "error", contentType = "note") {
                    SearchNote(library.error.orEmpty(), warning = true)
                }
                if (showLimit) item(key = "limit", contentType = "note") {
                    SearchNote("Your folders are very large, so some files may not be listed. Open file still reaches them.")
                }
                if (tab == HomeTab.Bookmarks) {
                    if (bookmarks.isEmpty()) {
                        item("no-bookmarks") {
                            EmptyMessage("No bookmarks yet", "Bookmarks you add in documents appear here.", EzIcons.BookmarkOutline)
                        }
                    } else {
                        items(bookmarks, key = { it.key }, contentType = { "bookmark" }) { bookmark ->
                            FileRow(
                                file = bookmark.file,
                                detail = bookmark.page?.let { page ->
                                    listOfNotNull("Page ${page + 1}", bookmark.label, bookmark.snippet?.let { "\u201C$it\u201D" }).joinToString(" \u00B7 ")
                                },
                                isPicked = bookmark.file in picked,
                                selecting = picked.isNotEmpty(),
                                onOpen = { onBookmark(bookmark) },
                                onToggle = { toggle(bookmark.file) },
                                onLongPress = { longPress(bookmark.file) },
                                marked = historyId != null && FileProgress.identity(bookmark.file.uri.toString()) == historyId,
                                playback = if (bookmark.file.kind == DocumentKind.Video) {
                                    remember(bookmark.file.uri, playbackRevision) { playbackFor(bookmark.file) }
                                } else null,
                                modifier = if (reducedMotion) Modifier else Modifier.animateItem(placementSpec = EzMotion.defaultSpatial()),
                            )
                        }
                    }
                } else if (showFolderEmpty) {
                    item("no-folders", contentType = "card") { NoFoldersCard(onAddFolder, RowGutter) }
                } else if (tab == HomeTab.Library && matching.isEmpty() && library.scanning) {
                    item("loading") {
                        SearchNote("Checking linked folders…")
                        LinearProgressIndicator(RowGutter.fillMaxWidth(), color = EzColors.Primary)
                    }
                } else if (matching.isEmpty() && showingContentSearch && (visibleHits.isNotEmpty() || contentSearch.indexing)) {
                    item("no-name-matches") { SearchNote("No matching file names.") }
                } else if (matching.isEmpty()) {
                    item("empty") {
                        val title = when {
                            query.isNotEmpty() -> "No matching file names"
                            tab == HomeTab.Recent -> "No recent files"
                            noFolders -> "No folders added"
                            else -> "No matching files"
                        }
                        val description = when {
                            query.isNotEmpty() -> "Try another search or category."
                            tab == HomeTab.Recent -> "Open a file to see it here."
                            noFolders -> "Add a folder to browse the files in it."
                            else -> "Try another search or category."
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            EmptyMessage(title, description)
                            if (showsAssistant) FilyMascotCallToAction(onAsk = onFily, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                        }
                    }
                } else {
                    items(matching, key = { "$section|${it.uri}" }, contentType = { "file" }) { file ->
                        FileRow(
                            file = file,
                            isPicked = file in picked,
                            selecting = picked.isNotEmpty(),
                            onOpen = { onFile(file) },
                            onToggle = { toggle(file) },
                            onLongPress = { longPress(file) },
                            actions = actionsFor(file),
                            marked = file.uri == markedUri,
                            playback = if (file.kind == DocumentKind.Video) {
                                remember(file.uri, playbackRevision) { playbackFor(file) }
                            } else null,
                            modifier = if (reducedMotion) Modifier else Modifier.animateItem(placementSpec = EzMotion.defaultSpatial()),
                        )
                    }
                }
                if (showingContentSearch) {
                    item("inside-files", contentType = "section") {
                        EzSectionPill(
                            "Inside files",
                            RowGutter,
                            count = visibleHits.size.takeIf { it > 0 }?.toString(),
                            compact = landscape,
                        )
                    }
                    if (contentSearch.indexing) item("indexing", contentType = "note") {
                        SearchNote("Indexing ${contentSearch.indexed} of ${contentSearch.total}")
                        LinearProgressIndicator(RowGutter.fillMaxWidth(), color = EzColors.Primary)
                    } else if (visibleHits.isEmpty()) item("no-inside", contentType = "note") {
                        SearchNote("No matches inside these files.")
                    }
                    items(visibleHits, key = { "inside|${it.file.uri}" }, contentType = { "snippet" }) { hit ->
                        val motion = if (reducedMotion) Modifier else Modifier.animateItem(placementSpec = EzMotion.defaultSpatial())
                        ContentMatchRow(hit, onClick = { onFile(hit.file) }, modifier = RowGutter.then(motion))
                    }
                }
            }
            if (landscape) {
                LandscapeLibraryRail(navItems, onOpen, onSettings, Modifier.align(Alignment.CenterStart))
                if (topVisible) GlassIconButton(
                    EzIcons.ArrowUp, "Go to top", backToTop,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                )
            } else {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { chromeHeight = it.height }
                        .navigationBarsPadding()
                        .padding(start = EzSpacing.Gutter, end = EzSpacing.Gutter, bottom = 10.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (tab != HomeTab.Bookmarks || topVisible) Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AnimatedVisibility(
                            visible = topVisible,
                            enter = EzMotion.edgeEnter(reducedMotion),
                            exit = EzMotion.edgeExit(reducedMotion),
                        ) { GlassIconButton(EzIcons.ArrowUp, "Go to top", backToTop) }
                        if (tab != HomeTab.Bookmarks) EzFloatingAction("Open file", EzIcons.Folder, onOpen)
                    }
                    EzNavigationBar(navItems, backdrop.blur(Modifier, EzShapes.Bar))
                }
            }
        }
    }

    if (confirmClear && !filyOpen) {
        EzDialog(
            onDismissRequest = { confirmClear = false },
            title = "Clear recent files?",
            text = "Your files stay in their original locations.",
            confirmText = "Clear",
            onConfirm = {
                confirmClear = false
                ezHaptics.confirm()
                onClear()
            },
            dismissText = "Cancel",
            onDismiss = { confirmClear = false },
        )
    }
    val folder = library.folders.firstOrNull { it.uri.toString() == removingFolder }
    if (folder != null && !filyOpen) {
        EzDialog(
            onDismissRequest = { removingFolder = null },
            title = "Remove ${folder.name}?",
            text = "Xfiles will stop listing this folder and give up access to it. Your files stay where they are.",
            confirmText = "Remove",
            onConfirm = {
                removingFolder = null
                ezHaptics.confirm()
                onRemoveFolder(folder.uri)
            },
            dismissText = "Keep",
            onDismiss = { removingFolder = null },
        )
    }
}

