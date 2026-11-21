package com.xfiles.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.SideEffect
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.core.documents.FileBookmark
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FolderLibrary
import com.xfiles.core.documents.LibraryContentSearch
import com.xfiles.core.documents.LibrarySearchHit
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzFloatingAction
import com.xfiles.design.EzNavigationBar
import com.xfiles.design.EzNavItem
import com.xfiles.design.EzShapes
import com.xfiles.design.LocalHapticsEnabled
import com.xfiles.design.glassCard
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.library.LibraryCategory
import com.xfiles.documents.library.libraryCategories

/** Most files one share sends: the system share sheet takes a bounded list. */
private const val MaxSharedFiles = 32

/** Side of a row's preview. */
private val ThumbnailSize = 56.dp

@Composable
fun LibraryScreen(
    recentFiles: List<FileEntry>,
    listing: LibraryListing,
    category: LibraryCategory,
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
    onClear: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (Uri) -> Unit,
    onRefresh: () -> Unit,
    onAbout: () -> Unit,
    onTools: () -> Unit,
    onFily: () -> Unit,
    onListedFiles: (List<FileEntry>) -> Unit,
) {
    val search = rememberTextFieldState()
    val query = search.text.toString().trim()
    var tab by rememberSaveable { mutableStateOf(HomeTab.Library) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var removingFolder by rememberSaveable { mutableStateOf<String?>(null) }
    // The multi-select set. It is cleared when the tab changes, and by the back gesture.
    val picked = remember { mutableStateListOf<FileEntry>() }

    LaunchedEffect(onQueryChange) {
        snapshotFlow { search.text.toString().trim() }.collect(onQueryChange)
    }
    LaunchedEffect(tab) { picked.clear() }
    BackHandler(enabled = picked.isNotEmpty()) { picked.clear() }

    val matching = if (tab == HomeTab.Recent) listing.recents else listing.files
    SideEffect(tab, matching, bookmarks) {
        onListedFiles(if (tab == HomeTab.Bookmarks) bookmarks.map { it.file } else matching)
    }
    val searchesInside = searchEnabled && tab == HomeTab.Library && !library.scanning && query.length >= 2
    SideEffect(query, searchesInside, library.files) {
        if (searchesInside) onSearchInside(query) else onStopSearch()
    }
    DisposableEffect(Unit) { onDispose(onStopSearch) }
    val window = LocalWindowInfo.current.containerDpSize
    val landscape = window.width >= 600.dp && window.width > window.height
    val navItems = listOf(
        EzNavItem("Library", EzIcons.Folder, tab == HomeTab.Library) { tab = HomeTab.Library },
        EzNavItem("Recent", EzIcons.History, tab == HomeTab.Recent) { tab = HomeTab.Recent },
        EzNavItem("Bookmarks", EzIcons.BookmarkOutline, tab == HomeTab.Bookmarks) { tab = HomeTab.Bookmarks },
        EzNavItem("Tools", EzIcons.Apps, false, onTools),
    )
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    fun toggle(file: FileEntry) {
        if (picked.contains(file)) picked.remove(file) else picked.add(file)
    }
    fun longPress(file: FileEntry) {
        if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        toggle(file)
    }
    fun openPicked() {
        val file = picked.single()
        picked.clear()
        onFile(file)
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize().padding(start = if (landscape) 112.dp else 16.dp, end = 16.dp)) {
            if (picked.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Xfiles", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    IconButton(onClick = onFily) { Icon(EzIcons.Mic, "Voice assistant", tint = EzColors.Primary) }
                    if (!landscape) IconButton(onClick = onAbout) { Icon(EzIcons.Info, "About Xfiles", tint = EzColors.Primary) }
                }
            } else {
                SelectionBar(
                    count = picked.size,
                    onOpen = if (picked.size == 1) ({ openPicked() }) else null,
                    onShare = { onShareFiles(picked.toList()) },
                    onClear = { picked.clear() },
                )
            }
            if (tab != HomeTab.Bookmarks) LibrarySearch(search)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tab == HomeTab.Library) item("folders") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onAddFolder) { Text("Add folder") }
                        if (library.folders.isNotEmpty()) {
                            FilterChip(
                                selected = searchEnabled,
                                onClick = { onSearchEnabled(!searchEnabled) },
                                label = { Text("Search inside files") },
                            )
                        }
                        if (library.folders.isNotEmpty()) TextButton(onClick = onRefresh, enabled = !library.scanning) { Text("Refresh") }
                        library.folders.forEach { folder ->
                            TextButton(onClick = { removingFolder = folder.uri.toString() }) {
                                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (tab == HomeTab.Library) item("filters") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LibraryCategory.entries.forEach { chip ->
                            FilterChip(selected = chip == category, onClick = { onCategory(chip) }, label = { Text(chip.label) })
                        }
                    }
                }
                item("heading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val title = when {
                            tab == HomeTab.Bookmarks -> "Bookmarks"
                            tab == HomeTab.Recent -> "Recent files"
                            category == LibraryCategory.All -> "In your folders"
                            else -> category.label
                        }

                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (tab == HomeTab.Recent && recentFiles.isNotEmpty()) {
                            TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                        }
                    }
                    if (library.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    library.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (library.reachedLimit && tab == HomeTab.Library) {
                        Text("Some files are outside the scan limit. Use Open file to reach them.", color = EzColors.TextSecondary)
                    }
                }
                if (tab == HomeTab.Bookmarks) {
                    if (bookmarks.isEmpty()) {
                        item("no-bookmarks") {
                            EmptyLibrary("No bookmarks yet", "Bookmarks you add in documents appear here.", EzIcons.BookmarkOutline)
                        }
                    } else {
                        items(bookmarks, key = { it.key }, contentType = { "bookmark" }) { bookmark ->
                            val description = bookmark.page?.let { "Page ${it + 1}" }
                            FileRow(
                                file = bookmark.file,
                                detail = description,
                                isPicked = bookmark.file in picked,
                                selecting = picked.isNotEmpty(),
                                onOpen = { onBookmark(bookmark) },
                                onToggle = { toggle(bookmark.file) },
                                onLongPress = { longPress(bookmark.file) },
                            )
                        }
                    }
                } else if (matching.isEmpty()) {
                    item("empty") {
                        val title = when {
                            query.isNotEmpty() -> "No matching file names"
                            tab == HomeTab.Recent -> "No recent files"
                            library.folders.isEmpty() -> "No folders added"
                            else -> "No matching files"
                        }
                        val description = when {
                            query.isNotEmpty() -> "Try another search or category."
                            tab == HomeTab.Recent -> "Open a file to see it here."
                            library.folders.isEmpty() -> "Add a folder to browse the files in it."
                            else -> null
                        }
                        EmptyLibrary(title, description)
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
                        )
                    }
                }
                if (searchesInside && contentSearch.query == query) {
                    item("inside-files") {
                        Text("Inside files", style = MaterialTheme.typography.titleLarge)
                        if (contentSearch.indexing) {
                            Text("Indexing ${contentSearch.indexed} of ${contentSearch.total}", color = EzColors.TextSecondary)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else if (contentSearch.hits.isEmpty()) {
                            Text("No matches inside these files.", color = EzColors.TextSecondary)
                        }
                    }
                    val visibleHits = contentSearch.hits.filter {
                        category == LibraryCategory.All || category in libraryCategories(it.file.name)
                    }
                    items(visibleHits, key = { "inside|${it.file.uri}" }, contentType = { "snippet" }) { hit ->
                        ContentMatchRow(hit, onClick = { onFile(hit.file) })
                    }
                }
            }
            if (!landscape) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (tab != HomeTab.Bookmarks) EzFloatingAction("Open file", EzIcons.Folder, onOpen)
                    EzNavigationBar(navItems)
                }
            }
        }
        if (landscape) LandscapeLibraryRail(navItems, onOpen, onAbout, Modifier.align(Alignment.CenterStart))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear recent files?") },
            text = { Text("Your files stay in their original locations.") },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Clear") }
            },
        )
    }
    val folder = library.folders.firstOrNull { it.uri.toString() == removingFolder }
    if (folder != null) {
        AlertDialog(
            onDismissRequest = { removingFolder = null },
            title = { Text("Remove ${folder.name}?") },
            text = { Text("Xfiles will stop listing this folder and give up access to it. Your files stay where they are.") },
            dismissButton = { TextButton(onClick = { removingFolder = null }) { Text("Keep") } },
            confirmButton = {
                TextButton(onClick = {
                    removingFolder = null
                    onRemoveFolder(folder.uri)
                }) { Text("Remove") }
            },
        )
    }
}

/** The selection bar: the count, Open for a single file, Share (up to 32) and Clear. */
@Composable
private fun SelectionBar(count: Int, onOpen: (() -> Unit)?, onShare: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count selected", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (onOpen != null) TextButton(onClick = onOpen) { Text("Open") }
        TextButton(onClick = onShare, enabled = count in 1..MaxSharedFiles) {
            Text(if (count > MaxSharedFiles) "Share (max $MaxSharedFiles)" else "Share")
        }
        TextButton(onClick = onClear) { Text("Clear") }
    }
}

@Composable
private fun LibrarySearch(search: TextFieldState) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).glassCard(EzShapes.Pill, 0.62f).padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(EzIcons.Search, null, Modifier.size(24.dp), tint = EzColors.Primary)
        BasicTextField(
            state = search,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = EzColors.Text),
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(EzColors.Primary),
            decorator = { field ->
                Box {
                    if (search.text.isEmpty()) {
                        Text("Search files, docs, sheets, and more", color = EzColors.TextSecondary, maxLines = 1)
                    }
                    field()
                }
            },
        )
        if (search.text.isNotEmpty()) {
            IconButton(onClick = search::clearText) { Icon(EzIcons.Close, "Clear search") }
        }
    }
}

@Composable
private fun EmptyLibrary(title: String, description: String?, icon: ImageVector = EzIcons.Folder) {
    Column(
        Modifier.fillMaxWidth().glassCard().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(32.dp), tint = EzColors.Primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        description?.let { Text(it, color = EzColors.TextSecondary, textAlign = TextAlign.Center) }
    }
}

/**
 * A file row. Tap opens it, or toggles it while a selection is active; long-press
 * starts a selection (with a haptic tick). Open and Share for a single file are
 * in the selection bar.
 */
@Composable
private fun FileRow(
    file: FileEntry,
    detail: String? = null,
    isPicked: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().glassCard()
            .combinedClickable(
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = onLongPress,
                onLongClickLabel = "Select",
            )
            .semantics { this.selected = isPicked }
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) Checkbox(checked = isPicked, onCheckedChange = null)
        FileThumbnail(file)
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }
        if (!selecting) Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary)
    }
}

/**
 * A 56 dp rounded preview for photos, videos and PDFs; the type icon otherwise,
 * while the preview loads, or when none can be made. Loading stops when the row leaves.
 */
@Composable
private fun FileThumbnail(file: FileEntry) {
    val icon = fileIcon(file.kind)
    val shape = RoundedCornerShape(12.dp)
    if (!LibraryThumbnails.supports(file.kind)) {
        Box(Modifier.size(ThumbnailSize), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = EzColors.Primary)
        }
        return
    }
    val context = LocalContext.current
    val sidePx = with(LocalDensity.current) { ThumbnailSize.roundToPx() }
    val preview by produceState<ImageBitmap?>(LibraryThumbnails.cached(file, sidePx)?.asImageBitmap(), file.uri, file.modifiedAt, sidePx) {
        value = (LibraryThumbnails.cached(file, sidePx)
            ?: LibraryThumbnails.load(context.contentResolver, file, sidePx))?.asImageBitmap()
    }
    Box(
        Modifier.size(ThumbnailSize).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = preview
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(icon, null, Modifier.size(28.dp), tint = EzColors.Primary)
        }
        if (file.kind == DocumentKind.Video) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp).background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(EzIcons.Play, "Video", Modifier.size(12.dp), tint = Color.White)
            }
        }
    }
}

private fun fileIcon(kind: DocumentKind): ImageVector = when (kind) {
    DocumentKind.Pdf -> EzIcons.PdfFile
    DocumentKind.Image -> EzIcons.Image
    DocumentKind.Audio -> EzIcons.Music
    DocumentKind.Video -> EzIcons.Movie
    else -> EzIcons.Document
}

private enum class HomeTab { Library, Recent, Bookmarks }

@Composable
private fun ContentMatchRow(hit: LibrarySearchHit, onClick: () -> Unit) {
    val highlight = EzColors.Selected
    val styled = remember(hit, highlight) {
        buildAnnotatedString {
            append(hit.snippet)
            for (range in hit.matches) {
                val start = range.first.coerceIn(0, hit.snippet.length)
                val end = (range.last + 1).coerceIn(start, hit.snippet.length)
                if (start < end) addStyle(SpanStyle(background = highlight), start, end)
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().glassCard().clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(hit.file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(styled, style = MaterialTheme.typography.bodyMedium)
    }
}
