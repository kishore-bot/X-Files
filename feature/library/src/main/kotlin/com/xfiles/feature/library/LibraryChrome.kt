package com.xfiles.feature.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import com.xfiles.design.FilyButton
import com.xfiles.design.GlassIconButton
import com.xfiles.design.PrimaryActionButton
import com.xfiles.design.glass
import com.xfiles.design.glassCard
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.library.LibraryCategory
import com.xfiles.documents.library.libraryCategories

internal enum class HomeTab { Library, Recent, Bookmarks }

/** App title and the actions for the current library tab. */
@Composable
internal fun Header(
    onSettings: (() -> Unit)?,
    onVoice: (() -> Unit)?,
    compact: Boolean,
    onFolders: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = EzSpacing.Gutter, vertical = if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Xfiles",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            color = EzColors.Text,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onFolders != null) GlassIconButton(EzIcons.Folder, "Manage folders", onFolders)
        if (onVoice != null) FilyButton(onVoice)
        if (onSettings != null) GlassIconButton(EzIcons.Settings, "Settings", onSettings)
    }
}

/** The search pill: a red search icon, one line of text, a clear button while there is text. */
@Composable
internal fun LibrarySearch(
    search: TextFieldState,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
) {
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
            modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.hasFocus) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = EzColors.Text),
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(EzColors.Primary),
            decorator = { field ->
                Box {
                    if (search.text.isEmpty()) {
                        Text("Search files", color = EzColors.TextSecondary, fontSize = 16.sp, maxLines = 1)
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
internal fun FilterRow(selected: LibraryCategory, onSelect: (LibraryCategory) -> Unit, compact: Boolean) {
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FolderManager(
    folders: List<LinkedFolder>,
    searchEnabled: Boolean,
    scanning: Boolean,
    onAdd: () -> Unit,
    onSearchEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: (String) -> Unit,
    onReconnect: (android.net.Uri) -> Unit,
) {
    FlowRow(
        RowGutter.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EzChip("Add folder", selected = false, onClick = onAdd, icon = EzIcons.Plus)
        if (folders.isNotEmpty()) {
            EzChip("Search inside files", selected = searchEnabled, onClick = { onSearchEnabled(!searchEnabled) })
            if (!scanning) EzChip("Refresh", selected = false, onClick = onRefresh)
        }
    }
    if (scanning) {
        LinearProgressIndicator(RowGutter.fillMaxWidth().padding(top = 8.dp), color = EzColors.Primary)
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        contentPadding = PaddingValues(horizontal = EzSpacing.Gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folders, key = { it.uri.toString() }) { folder ->
            Row(
                Modifier.fillMaxWidth().glassCard().padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(EzIcons.Folder, null, tint = EzColors.Primary, modifier = Modifier.size(18.dp))
                Text(
                    folder.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = EzColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when (folder.access) {
                    com.xfiles.core.documents.FolderAccess.Available -> Unit
                    com.xfiles.core.documents.FolderAccess.Reconnect -> EzTextButton("Reconnect", { onReconnect(folder.uri) })
                    com.xfiles.core.documents.FolderAccess.Retry -> EzTextButton("Retry", onRefresh)
                }
                BarIconButton(EzIcons.Close, "Remove ${folder.name}", { onRemove(folder.uri.toString()) }, tint = EzColors.TextSecondary)
            }
        }
    }
}

/** The first-run card: explains folders and offers the one main action. */
@Composable
internal fun NoFoldersCard(onAddFolder: () -> Unit, modifier: Modifier) {
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
internal fun SectionTitle(
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
internal fun SearchNote(text: String, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) EzColors.Accent else EzColors.TextSecondary,
        modifier = RowGutter.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** The selection bar: the count, Open for a single file, Share (up to 32) and Clear. */
@Composable
internal fun SelectionBar(count: Int, onOpen: (() -> Unit)?, onShare: () -> Unit, onClear: () -> Unit) {
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
internal fun EmptyMessage(title: String, body: String, icon: ImageVector = EzIcons.Folder) {
    EzEmptyCard(title, body, icon, Modifier.padding(horizontal = EzSpacing.Gutter, vertical = 12.dp))
}

/**
 * A file row. Tap opens it, or toggles it while a selection is active; long-press
 * starts a selection (with a haptic tick). Open and Share for a single file are
 * in the selection bar.
 */
