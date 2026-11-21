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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.xfiles.core.documents.FileBookmark
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FolderLibrary
import com.xfiles.core.documents.LibraryContentSearch
import com.xfiles.core.documents.LibrarySearchHit
import com.xfiles.core.documents.LinkedFolder
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
import com.xfiles.design.LocalHapticsEnabled
import com.xfiles.design.PrimaryActionButton
import com.xfiles.design.floatingGlass
import com.xfiles.design.glass
import com.xfiles.design.glassCard
import com.xfiles.design.pressScale
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.library.LibraryCategory
import com.xfiles.documents.library.libraryCategories

/** Most files one share sends: the system share sheet takes a bounded list. */
private const val MaxSharedFiles = 32

/** Side of a row's preview. */
private val ThumbnailSize = 56.dp

/** Every filter chip, in display order; the list never changes, so it is built once. */
private val Categories: List<LibraryCategory> = LibraryCategory.entries

/** Gutter every list item uses; the list itself has no horizontal padding, so rows align with the header. */
private val RowGutter = Modifier.padding(horizontal = EzSpacing.Gutter)

/** Room the floating Open file button and the tab bar take at the end of the list. */
private val PortraitClearance = 172.dp

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
    val navItems = remember(tab, onTools) {
        listOf(
            EzNavItem("Library", EzIcons.Folder, tab == HomeTab.Library) { tab = HomeTab.Library },
            EzNavItem("Recent", EzIcons.History, tab == HomeTab.Recent) { tab = HomeTab.Recent },
            EzNavItem("Bookmarks", EzIcons.BookmarkOutline, tab == HomeTab.Bookmarks) { tab = HomeTab.Bookmarks },
            EzNavItem("Tools", EzIcons.Apps, false, onTools),
        )
    }
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
    val noFolders = library.folders.isEmpty()
    // The list scrolls under the floating button and the tab bar, and clears them at the end.
    val bottomClearance = if (landscape) 24.dp else PortraitClearance
    val startClearance = if (landscape) 112.dp else 0.dp
    val showsAssistant = tab == HomeTab.Library && (query.isNotEmpty() || !noFolders)

    Box(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
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
                        onAbout = onAbout.takeUnless { landscape },
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
                    )
                }
                if (noFolders) item(key = "no-folders", contentType = "card") {
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
                            detail = bookmark.page?.let { "Page ${it + 1}" },
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
            LandscapeLibraryRail(navItems, onOpen, onAbout, Modifier.align(Alignment.CenterStart))
        } else {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
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

    if (confirmClear) {
        EzDialog(
            onDismissRequest = { confirmClear = false },
            title = "Clear recent files?",
            text = "Your files stay in their original locations.",
            confirmText = "Clear",
            onConfirm = {
                confirmClear = false
                onClear()
            },
            dismissText = "Cancel",
            onDismiss = { confirmClear = false },
        )
    }
    val folder = library.folders.firstOrNull { it.uri.toString() == removingFolder }
    if (folder != null) {
        EzDialog(
            onDismissRequest = { removingFolder = null },
            title = "Remove ${folder.name}?",
            text = "Xfiles will stop listing this folder and give up access to it. Your files stay where they are.",
            confirmText = "Remove",
            onConfirm = {
                removingFolder = null
                onRemoveFolder(folder.uri)
            },
            dismissText = "Keep",
            onDismiss = { removingFolder = null },
        )
    }
}

private enum class HomeTab { Library, Recent, Bookmarks }

/** Menu, the app's name, Fily's mic and About (when there is one). */
@Composable
private fun Header(
    onAbout: (() -> Unit)?,
    onVoice: (() -> Unit)?,
    compact: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = EzSpacing.Gutter, end = EzSpacing.Gutter, top = if (compact) 4.dp else 12.dp, bottom = if (compact) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Xfiles",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            color = EzColors.Text,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onVoice != null) MicButton(onVoice)
        if (onAbout != null) GlassIconButton(EzIcons.Info, "About Xfiles", onAbout)
    }
}

/** The round glass button that opens Fily; Fily's mascot sits inside it. */
@Composable
private fun MicButton(onVoice: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    Box(
        Modifier
            .size(48.dp)
            .pressScale(interaction, 0.9f)
            .floatingGlass(CircleShape)
            .clickable(interaction, indication = null, role = Role.Button) {
                if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onVoice()
            }
            .semantics { contentDescription = "Voice assistant" },
        contentAlignment = Alignment.Center,
    ) {
        FilyMascot(size = 40.dp)
    }
}

/** The search pill: a red search icon, one line of text, a clear button while there is text. */
@Composable
private fun LibrarySearch(search: TextFieldState, compact: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .glassCard(EzShapes.Pill, 0.62f)
            .padding(start = 18.dp, end = 8.dp, top = if (compact) 6.dp else 12.dp, bottom = if (compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(EzIcons.Search, null, Modifier.size(EzSpacing.BarIcon), tint = EzColors.Primary)
        BasicTextField(
            state = search,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = EzColors.Text),
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(EzColors.Primary),
            decorator = { field ->
                Box {
                    if (search.text.isEmpty()) {
                        Text("Search files, docs, sheets, and more", color = EzColors.TextSecondary, fontSize = 16.sp, maxLines = 1)
                    }
                    field()
                }
            },
        )
        if (search.text.isNotEmpty()) {
            BarIconButton(EzIcons.Close, "Clear search", { search.clearText() }, tint = EzColors.TextSecondary)
        }
    }
}

/** The filter chips in one row that scrolls sideways; each carries its type's icon and colour. */
@Composable
private fun FilterRow(selected: LibraryCategory, onSelect: (LibraryCategory) -> Unit, compact: Boolean) {
    LazyRow(
        Modifier.fillMaxWidth(),
        // Room above and below for the chips' shadows, which the row would otherwise clip.
        contentPadding = PaddingValues(horizontal = EzSpacing.Gutter, vertical = if (compact) 2.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Categories, key = { it.name }, contentType = { "chip" }) { chip ->
            val colors = chip.typeColors()
            EzChip(
                label = chip.label,
                selected = selected == chip,
                onClick = { onSelect(chip) },
                icon = chip.icon(),
                iconTint = colors?.let { EzBadgeColors.ink(it) } ?: EzColors.Text,
            )
        }
    }
}

/** The folders as glass chips, with Add folder, Search inside files and Refresh beside them. */
@Composable
private fun FolderRow(
    folders: List<LinkedFolder>,
    searchEnabled: Boolean,
    scanning: Boolean,
    onAdd: () -> Unit,
    onSearchEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = EzSpacing.Gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "add", contentType = "add") {
            EzChip(label = "Add folder", selected = false, onClick = onAdd, icon = EzIcons.Plus, iconTint = EzColors.Primary)
        }
        if (folders.isNotEmpty()) {
            item(key = "search-inside", contentType = "toggle") {
                EzChip(label = "Search inside files", selected = searchEnabled, onClick = { onSearchEnabled(!searchEnabled) })
            }
            item(key = "refresh", contentType = "toggle") {
                EzChip(label = "Refresh", selected = false, onClick = onRefresh, icon = null)
            }
        }
        items(folders, key = { it.uri.toString() }, contentType = { "folder" }) { folder ->
            Row(
                Modifier.widthIn(max = 240.dp).glass(EzShapes.Pill, 0.5f).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(EzIcons.Folder, null, tint = EzColors.Primary, modifier = Modifier.size(18.dp))
                Text(
                    folder.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = EzColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                BarIconButton(EzIcons.Close, "Remove ${folder.name}", { onRemove(folder.uri.toString()) }, tint = EzColors.TextSecondary)
            }
        }
    }
}

/** The first-run card: explains folders and offers the one main action. */
@Composable
private fun NoFoldersCard(onAddFolder: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().glass(EzShapes.Card, 0.6f).padding(EzSpacing.Panel),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Bring in your documents", style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
        Text(
            "Add a folder, such as Documents, and Xfiles lists every file in it. Files stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = EzColors.TextSecondary,
        )
        Text(
            "Android doesn't allow adding Download as a folder. Use Open file for those files.",
            style = MaterialTheme.typography.bodySmall,
            color = EzColors.TextSecondary,
        )
        PrimaryActionButton("Add folder", EzIcons.Plus, onAddFolder, Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

/** A section heading in a glass pill, with a count, a busy spinner and a trailing action. */
@Composable
private fun SectionTitle(
    title: String,
    count: String?,
    busy: Boolean = false,
    onClear: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    EzSectionPill(
        title = title,
        modifier = RowGutter,
        count = count,
        compact = compact,
        trailing = {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = EzColors.Primary)
            if (onClear != null) {
                Box(Modifier.weight(1f))
                EzTextButton("Clear", onClear)
            }
        },
    )
}

/** A quiet line under a section title, or a warning when [warning]. */
@Composable
private fun SearchNote(text: String, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) EzColors.Accent else EzColors.TextSecondary,
        modifier = RowGutter.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** The selection bar: the count, Open for a single file, Share (up to 32) and Clear. */
@Composable
private fun SelectionBar(count: Int, onOpen: (() -> Unit)?, onShare: () -> Unit, onClear: () -> Unit) {
    Row(
        RowGutter
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .glassCard(EzShapes.Pill, 0.55f)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
        if (onOpen != null) EzTextButton("Open", onOpen)
        EzTextButton(
            if (count > MaxSharedFiles) "Share (max $MaxSharedFiles)" else "Share",
            onShare,
            enabled = count in 1..MaxSharedFiles,
        )
        EzTextButton("Clear", onClear)
    }
}

/** The empty and no-results card: a red icon, a title and one line of explanation. */
@Composable
private fun EmptyMessage(title: String, body: String, icon: ImageVector = EzIcons.Folder) {
    EzEmptyCard(title, body, icon, Modifier.padding(horizontal = EzSpacing.Gutter, vertical = 12.dp))
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
    val interaction = remember { MutableInteractionSource() }
    Row(
        RowGutter
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressScale(interaction, 0.985f)
            .glassCard(EzShapes.Card, 0.5f)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = onLongPress,
                onLongClickLabel = "Select",
            )
            .semantics { this.selected = isPicked }
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (selecting) Checkbox(checked = isPicked, onCheckedChange = null)
        FileThumbnail(file)
        Column(Modifier.weight(1f)) {
            Text(file.name, style = FileNameStyle, color = EzColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, fontSize = 12.sp, color = EzColors.TextSecondary, modifier = Modifier.padding(top = 3.dp)) }
        }
        if (!selecting) Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary)
    }
}

/**
 * A 56 dp rounded preview for photos, videos and PDFs; a coloured type badge
 * otherwise, while the preview loads, or when none can be made. Loading stops when the row leaves.
 */
@Composable
private fun FileThumbnail(file: FileEntry) {
    val badge = remember(file.name) { fileBadge(file.name) }
    if (!LibraryThumbnails.supports(file.kind)) {
        Box(Modifier.size(ThumbnailSize), contentAlignment = Alignment.Center) {
            FileTypeTile(badge.first, badge.second, Modifier.size(width = 40.dp, height = 46.dp))
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
        Modifier.size(ThumbnailSize).clip(ThumbnailShape).background(EzColors.PageOutline.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        val image = preview
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            FileTypeTile(badge.first, badge.second, Modifier.size(width = 32.dp, height = 37.dp))
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

private val ThumbnailShape = RoundedCornerShape(12.dp)

/** The badge's label (the extension, or FILE) and its colours, by type. */
private fun fileBadge(name: String): Pair<String, Pair<Color, Color>> {
    val extension = name.substringAfterLast('.', "").uppercase()
    val label = if (extension.isEmpty() || extension.length > 4) "FILE" else extension
    val colors = when (extension.lowercase()) {
        "pdf" -> EzBadgeColors.Red
        "doc", "docx", "docm", "dotx", "odt", "rtf" -> EzBadgeColors.Blue
        "xls", "xlsx", "xlsm", "csv", "tsv", "ods" -> EzBadgeColors.Green
        "ppt", "pptx", "pptm", "odp", "pps", "ppsx" -> EzBadgeColors.Orange
        "epub" -> EzBadgeColors.Indigo
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> EzBadgeColors.Teal
        "mp4", "m4v", "mov", "webm", "mkv", "3gp" -> EzBadgeColors.Plum
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> EzBadgeColors.Pink
        "zip", "rar", "7z", "tar", "gz" -> EzBadgeColors.Grey
        "txt", "md", "log", "text", "html", "htm" -> EzBadgeColors.Steel
        else -> EzBadgeColors.Slate
    }
    return label to colors
}

/** The icon of a filter chip, or null for All. */
private fun LibraryCategory.icon(): ImageVector? = when (this) {
    LibraryCategory.All -> null
    LibraryCategory.Pdf -> EzIcons.PdfFile
    LibraryCategory.Docs -> EzIcons.Document
    LibraryCategory.Slides -> EzIcons.Play
    LibraryCategory.Sheets -> EzIcons.Table
    LibraryCategory.Books -> EzIcons.Book
    LibraryCategory.Images -> EzIcons.Image
    LibraryCategory.Media -> EzIcons.Movie
    LibraryCategory.Text -> EzIcons.TextT
    LibraryCategory.Code -> EzIcons.Code
    LibraryCategory.Data -> EzIcons.Database
    LibraryCategory.Archives -> EzIcons.Archive
}

/** The colour pair of a filter chip's icon, or null for All. */
private fun LibraryCategory.typeColors(): Pair<Color, Color>? = when (this) {
    LibraryCategory.All -> null
    LibraryCategory.Pdf -> EzBadgeColors.Red
    LibraryCategory.Docs -> EzBadgeColors.Blue
    LibraryCategory.Slides -> EzBadgeColors.Orange
    LibraryCategory.Sheets -> EzBadgeColors.Green
    LibraryCategory.Books -> EzBadgeColors.Purple
    LibraryCategory.Images -> EzBadgeColors.Teal
    LibraryCategory.Media -> EzBadgeColors.Plum
    LibraryCategory.Text -> EzBadgeColors.Steel
    LibraryCategory.Code -> EzBadgeColors.Violet
    LibraryCategory.Data -> EzBadgeColors.Indigo
    LibraryCategory.Archives -> EzBadgeColors.Grey
}

@Composable
private fun ContentMatchRow(hit: LibrarySearchHit, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.985f)
            .glassCard(EzShapes.Card, 0.5f)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(hit.file.name, style = MaterialTheme.typography.titleSmall, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(styled, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
    }
}

/** "report.final.pdf" -> "report.final"; a name with no extension, or only one, stays as it is. */
internal fun String.withoutExtension(): String {
    val dot = lastIndexOf('.')
    return if (dot <= 0 || length - dot > 6) this else substring(0, dot)
}

/** "1h 12m", "25m", "40s": how a person says time left. */
internal fun durationWords(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes >= 1 -> "${minutes}m"
        else -> "${(ms / 1000).coerceAtLeast(1)}s"
    }
}
