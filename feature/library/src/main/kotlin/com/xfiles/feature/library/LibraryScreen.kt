package com.xfiles.feature.library

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.xfiles.design.glassCard
import com.xfiles.documents.DocumentKind

@Composable
fun LibraryScreen(
    recentFiles: List<FileEntry>,
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
    var selected by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var removingFolder by rememberSaveable { mutableStateOf<String?>(null) }

    val matching = remember(query, tab, selected, recentFiles, library.files) {
        val source = if (tab == HomeTab.Recent) recentFiles else library.files
        source.filter { file ->
            (tab != HomeTab.Library || selected.kind == null || file.kind == selected.kind) && file.name.contains(query, ignoreCase = true)
        }
    }
    LaunchedEffect(tab, matching, bookmarks) {
        onListedFiles(if (tab == HomeTab.Bookmarks) bookmarks.map { it.file } else matching)
    }
    val searchesInside = searchEnabled && tab == HomeTab.Library && !library.scanning && query.length >= 2
    LaunchedEffect(query, searchesInside, library.files) {
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
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize().padding(start = if (landscape) 112.dp else 16.dp, end = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Xfiles", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onFily) { Icon(EzIcons.Mic, "Voice assistant", tint = EzColors.Primary) }
                if (!landscape) IconButton(onClick = onAbout) { Icon(EzIcons.Info, "About Xfiles", tint = EzColors.Primary) }
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
                        LibraryFilter.entries.forEach { filter ->
                            FilterChip(selected = filter == selected, onClick = { selected = filter }, label = { Text(filter.label) })
                        }
                    }
                }
                item("heading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val title = when {
                            tab == HomeTab.Bookmarks -> "Bookmarks"
                            tab == HomeTab.Recent -> "Recent files"
                            selected == LibraryFilter.All -> "In your folders"
                            else -> selected.label
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
                            FileRow(bookmark.file, detail = description, onClick = { onBookmark(bookmark) })
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
                        FileRow(file, onClick = { onFile(file) })
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
                    val visibleHits = contentSearch.hits.filter { selected.kind == null || it.file.kind == selected.kind }
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

@Composable
private fun FileRow(file: FileEntry, detail: String? = null, onClick: () -> Unit) {
    val icon = when (file.kind) {
        DocumentKind.Pdf -> EzIcons.PdfFile
        DocumentKind.Image -> EzIcons.Image
        DocumentKind.Audio -> EzIcons.Music
        DocumentKind.Video -> EzIcons.Movie
        else -> EzIcons.Document
    }
    Row(
        Modifier.fillMaxWidth().glassCard().clickable(onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = EzColors.Primary)
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }
        Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary)
    }
}

private enum class HomeTab { Library, Recent, Bookmarks }

private enum class LibraryFilter(val label: String, val kind: DocumentKind? = null) {
    All("All"),
    Pdf("PDF", DocumentKind.Pdf),
    Document("Documents", DocumentKind.Document),
    Text("Text", DocumentKind.Text),
    Image("Images", DocumentKind.Image),
    Video("Video", DocumentKind.Video),
    Audio("Audio", DocumentKind.Audio),
}

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
