package com.xfiles.feature.library

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.xfiles.core.documents.FolderLibrary
import com.xfiles.core.documents.LibraryContentSearch
import com.xfiles.core.documents.LibrarySearchHit
import com.xfiles.core.documents.LinkedFolder
import com.xfiles.core.documents.canPrint
import com.xfiles.design.BarIconButton
import com.xfiles.design.EzBadgeColors
import com.xfiles.design.EzChip
import com.xfiles.design.EzColors
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
import com.xfiles.design.PrimaryActionButton
import com.xfiles.design.floatingGlass
import com.xfiles.design.glass
import com.xfiles.design.glassCard
import com.xfiles.design.pressScale
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.library.LibraryCategory
import com.xfiles.documents.library.libraryCategories

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
    lastImage: String? = null,
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
    val filyOpen = LocalFilyOpen.current
    LaunchedEffect(filyOpen) {
        if (filyOpen) {
            toolsOpen = false
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
    val imageIndex = remember(matching, lastImage) {
        val id = lastImage?.let { com.xfiles.core.documents.FileProgress.identity(it) }
        if (id == null) -1 else matching.indexOfFirst { com.xfiles.core.documents.FileProgress.identity(it.uri.toString()) == id }
    }
    val markedImage = matching.getOrNull(imageIndex)?.uri
    val imageTab = tab == HomeTab.Library && category == LibraryCategory.Images
    // Header, search, filters, folders, and list title precede the file rows.
    val imageStart = 5 + (if (landscape) 0 else 1) + (if (noFolders) 1 else 0) +
        (if (library.error != null) 1 else 0) + (if (library.reachedLimit) 1 else 0)
    LaunchedEffect(imageTab, imageIndex, imageStart, lastImage, query) {
        if (imageTab && query.isEmpty() && imageIndex >= 0) listState.scrollToItem(imageStart + imageIndex)
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(start = startClearance),
            contentPadding = PaddingValues(top = if (landscape) 4.dp else 12.dp, bottom = bottomClearance),
            verticalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 8.dp),
        ) {
            if (picked.isNotEmpty()) {
                item(key = "selection", contentType = "actions") {
                    SelectionBar(
                        count = picked.size,
                        onOpen = if (picked.size == 1) ({ openPicked() }) else null,
                        onShare = { onShareFiles(picked.toList()) },
                        onClear = { picked.clear() },
                    )
                }
            } else {
                item(key = "header", contentType = "header") {
                    Header(
                        onSettings = onSettings.takeUnless { landscape },
                        onVoice = onFily,
                        compact = landscape,
                    )
                }
            }
            if (tab != HomeTab.Bookmarks) item(key = "search", contentType = "search") {
                LibrarySearch(search, compact = landscape, modifier = RowGutter.padding(vertical = 4.dp))
            }
            if (tab == HomeTab.Library) item(key = "chips", contentType = "chips") {
                FilterRow(category, onCategory, compact = landscape)
            }
            if (tab == HomeTab.Library) {
                if (!landscape) item(key = "folders-title", contentType = "section") {
                    EzSectionPill("Your folders", RowGutter)
                }
                item(key = "folders", contentType = "strip") {
                    FolderRow(
                        folders = library.folders,
                        searchEnabled = searchEnabled,
                        scanning = library.scanning,
                        onAdd = onAddFolder,
                        onSearchEnabled = onSearchEnabled,
                        onRefresh = onRefresh,
                        onRemove = { removingFolder = it },
                        onReconnect = onReconnectFolder,
                    )
                }
                if (noFolders && !library.scanning) item(key = "no-folders", contentType = "card") {
                    NoFoldersCard(onAddFolder, RowGutter)
                }
            }
            if (tab == HomeTab.Library || tab == HomeTab.Recent) {
                item(key = "list-title", contentType = "section") {
                    val title = when {
                        tab == HomeTab.Recent -> "Recent files"
                        category == LibraryCategory.All -> "In your folders"
                        else -> category.label
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
            if (tab == HomeTab.Library && library.error != null) item(key = "error", contentType = "note") {
                SearchNote(library.error.orEmpty(), warning = true)
            }
            if (tab == HomeTab.Library && library.reachedLimit) item(key = "limit", contentType = "note") {
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
                        )
                    }
                }
            } else if (tab == HomeTab.Library && matching.isEmpty() && library.scanning) {
                item("loading") {
                    SearchNote("Checking linked folders…")
                    LinearProgressIndicator(RowGutter.fillMaxWidth(), color = EzColors.Primary)
                }
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
                items(matching, key = { it.uri.toString() }, contentType = { "file" }) { file ->
                    FileRow(
                        file = file,
                        isPicked = file in picked,
                        selecting = picked.isNotEmpty(),
                        onOpen = { onFile(file) },
                        onToggle = { toggle(file) },
                        onLongPress = { longPress(file) },
                        actions = actionsFor(file),
                        marked = imageTab && file.uri == markedImage,
                    )
                }
            }
            if (searchesInside && contentSearch.query == query) {
                item("inside-files", contentType = "section") {
                    EzSectionPill(
                        "Inside files",
                        RowGutter,
                        count = contentSearch.hits.size.takeIf { it > 0 }?.toString(),
                        compact = landscape,
                    )
                }
                if (contentSearch.indexing) item("indexing", contentType = "note") {
                    SearchNote("Indexing ${contentSearch.indexed} of ${contentSearch.total}")
                    LinearProgressIndicator(RowGutter.fillMaxWidth(), color = EzColors.Primary)
                } else if (contentSearch.hits.isEmpty()) item("no-inside", contentType = "note") {
                    SearchNote("No matches inside these files.")
                }
                val visibleHits = contentSearch.hits.filter {
                    category == LibraryCategory.All || category in libraryCategories(it.file.name)
                }
                items(visibleHits, key = { "inside|${it.file.uri}" }, contentType = { "snippet" }) { hit ->
                    ContentMatchRow(hit, onClick = { onFile(hit.file) }, modifier = RowGutter)
                }
            }
        }
        if (landscape) {
            LandscapeLibraryRail(navItems, onOpen, onSettings, Modifier.align(Alignment.CenterStart))
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
                if (tab != HomeTab.Bookmarks) EzFloatingAction("Open file", EzIcons.Folder, onOpen)
                EzNavigationBar(navItems)
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

