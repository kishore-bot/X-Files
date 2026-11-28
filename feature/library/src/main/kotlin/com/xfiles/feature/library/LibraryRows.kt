package com.xfiles.feature.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.semantics.stateDescription
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
import com.xfiles.design.EzDialog
import com.xfiles.design.EzEmptyCard
import com.xfiles.design.EzFloatingAction
import com.xfiles.design.EzIcons
import com.xfiles.design.EzNavItem
import com.xfiles.design.EzNavigationBar
import com.xfiles.design.EzSectionPill
import com.xfiles.design.EzShapes
import com.xfiles.design.LocalReducedMotion
import com.xfiles.design.EzGlassDropdown
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
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.DocumentKind
import com.xfiles.documents.formatPlaybackTime
import com.xfiles.documents.library.LibraryCategory
import com.xfiles.documents.library.libraryCategories

@Composable
internal fun FileRow(
    file: FileEntry,
    detail: String? = null,
    isPicked: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    actions: FileRowActions? = null,
    marked: Boolean = false,
    playback: PlaybackProgress? = null,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val historyTint by animateColorAsState(
        if (marked) EzColors.Selected else Color.Transparent,
        if (reducedMotion) snap() else tween(180),
        label = "file history tint",
    )
    val historyOutline by animateColorAsState(
        if (marked) EzColors.Accent.copy(alpha = 0.58f) else Color.Transparent,
        if (reducedMotion) snap() else tween(180),
        label = "file history outline",
    )
    val interaction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        RowGutter.then(modifier)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressScale(interaction, 0.985f)
            .glassCard(EzShapes.Card, 0.5f)
            .background(historyTint, EzShapes.Card)
            .border(1.dp, historyOutline, EzShapes.Card)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = onLongPress,
                onLongClickLabel = "Select",
            )
            .semantics {
                this.selected = isPicked
                if (marked) stateDescription = "Last viewed"
            }
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (selecting) Checkbox(checked = isPicked, onCheckedChange = null)
        FileThumbnail(file)
        Column(Modifier.weight(1f)) {
            Text(file.name, style = FileNameStyle, color = EzColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, fontSize = 12.sp, color = EzColors.TextSecondary, modifier = Modifier.padding(top = 3.dp)) }
            if (playback != null) {
                val position = formatPlaybackTime(playback.positionMs)
                val duration = playback.durationMs
                Text(
                    if (duration == null) "Resume at $position" else "$position / ${formatPlaybackTime(duration)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (duration != null) {
                    LinearProgressIndicator(
                        progress = { (playback.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = EzColors.Accent,
                    )
                }
            }
        }
        if (marked) {
            Box(
                Modifier.size(8.dp)
                    .background(EzColors.Primary, androidx.compose.foundation.shape.CircleShape),
            )
        }
        if (!selecting) {
            if (actions != null) {
                Box {
                    GlassIconButton(
                        icon = EzIcons.More,
                        contentDescription = "More actions for ${file.name}",
                        onClick = { menuOpen = true },
                        size = 40.dp,
                    )
                    EzGlassDropdown(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        FileMenuItem("Open", EzIcons.Document) {
                            menuOpen = false
                            onOpen()
                        }
                        FileMenuItem("Share original", EzIcons.Share) {
                            menuOpen = false
                            actions.onShare()
                        }
                        FileMenuItem("Print", EzIcons.Print, enabled = actions.canPrint) {
                            menuOpen = false
                            actions.onPrint()
                        }
                        FileMenuItem("Create archive", EzIcons.Archive) {
                            menuOpen = false
                            actions.onCompress()
                        }
                        actions.onExtract?.let { extract ->
                            FileMenuItem("Extract", EzIcons.Folder) {
                                menuOpen = false
                                extract()
                            }
                        }
                        FileMenuItem(
                            if (isPicked) "Unselect" else "Select for sharing",
                            EzIcons.ListLines,
                            enabled = isPicked || actions.selectable,
                        ) {
                            menuOpen = false
                            onToggle()
                        }
                        actions.onRemove?.let { remove ->
                            FileMenuItem("Remove from recents", EzIcons.Close) {
                                menuOpen = false
                                remove()
                            }
                        }
                    }
                }
            } else {
                Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary)
            }
        }
    }
}

/** What a file row's More menu offers. Bookmarks have no menu; Recent rows can also remove themselves from recents. */
internal class FileRowActions(
    val canPrint: Boolean,
    val selectable: Boolean,
    val onShare: () -> Unit,
    val onPrint: () -> Unit,
    val onRemove: (() -> Unit)?,
    val onCompress: () -> Unit,
    /** Null unless the file is a zip. */
    val onExtract: (() -> Unit)?,
)

@Composable
internal fun FileMenuItem(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * A 56 dp rounded preview for photos, videos and PDFs; a coloured type badge
 * otherwise, while the preview loads, or when none can be made. Loading stops when the row leaves.
 */
@Composable
internal fun FileThumbnail(file: FileEntry) {
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

internal val ThumbnailShape = RoundedCornerShape(12.dp)

/** The badge's label (the extension, or FILE) and its colours, by type. */
internal fun fileBadge(name: String): Pair<String, Pair<Color, Color>> {
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
internal fun LibraryCategory.icon(): ImageVector? = when (this) {
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
internal fun LibraryCategory.typeColors(): Pair<Color, Color>? = when (this) {
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
internal fun ContentMatchRow(hit: LibrarySearchHit, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
