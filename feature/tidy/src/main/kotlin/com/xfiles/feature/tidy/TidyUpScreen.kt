package com.xfiles.feature.tidy

import androidx.compose.animation.CapturedAnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzGlassDropdown
import com.xfiles.design.BarIconButton
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzSectionHeader
import com.xfiles.design.EzTopBar
import com.xfiles.design.EzShapes
import com.xfiles.design.HapticOnChange
import com.xfiles.design.glassBar
import com.xfiles.design.glassCard
import com.xfiles.design.rememberHaptics
import com.xfiles.tidy.IdenticalGroup
import com.xfiles.tidy.SimilarGroup
import com.xfiles.tidy.TidyCollection
import com.xfiles.tidy.TidyFile
import com.xfiles.tidy.TidyReport
import java.text.DateFormat
import java.util.Date

private enum class TidySection(val label: String) {
    All("All"),
    Identical("Identical"),
    Documents("Similar documents"),
    Photos("Similar photos"),
    Collections("Collections"),
}

/**
 * Tidy up: identical files, similar documents and photos, and collections of receipts, tickets and
 * statements across the folders the user chose. Files open as usual; deleting asks first, every time.
 */
@Composable
fun TidyUpScreen(viewModel: TidyViewModel, onBack: () -> Unit, onOpen: (TidyFile) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<TidyFile?>(null) }
    val haptics = rememberHaptics()
    HapticOnChange(state.report) { if (it != null) confirm() }
    HapticOnChange(state.failure) { if (it != null) reject() }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EzTopBar("Tidy up", onBack, backLabel = "Back", gutter = 0.dp, actions = {
            if (state.report != null && state.progress == null) BarIconButton(TidyIcons.Refresh, "Scan again", viewModel::scan)
        })
        CapturedAnimatedVisibility(state.progress != null) {
            state.progress?.let { progress ->
                Column(Modifier.fillMaxWidth().glassCard().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(progress.label, style = MaterialTheme.typography.bodyMedium)
                    val fraction = progress.fraction
                    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = viewModel::cancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
            }
        }
        state.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { notice ->
            Row(Modifier.fillMaxWidth().glassCard().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = viewModel::dismissNotice) { Icon(EzIcons.Close, "Dismiss") }
            }
        }
        val report = state.report
        when {
            report == null && state.progress == null -> Intro(state.hasFolders, viewModel::scan)
            report == null -> StatusCard(
                "Looking through your folders",
                "The first scan reads each file once. Later scans only look at files that are new or changed.",
            )
            else -> Results(report, onOpen = onOpen, onShare = viewModel::share, onDelete = if (state.canDelete) ({ file: TidyFile -> pendingDelete = file }) else null)
        }
    }
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = EzShapes.Dialog,
            title = { Text("Delete this duplicate?") },
            text = {
                Text(
                    "“${file.name}” will be permanently deleted from its folder. The first matching copy is kept, " +
                        "and both files are compared again before deletion. This can't be undone.",
                )
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    haptics.confirm()
                    viewModel.delete(file)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun StatusCard(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().glassCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(TidyIcons.Sparkles, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
        action?.invoke()
    }
}

@Composable
private fun Intro(hasFolders: Boolean, onScan: () -> Unit) = StatusCard(
    "Tidy up your folders",
    if (hasFolders) {
        "Find identical files, similar documents and photos, and gather resumes, banking documents, receipts and tickets " +
            "into collections. Everything happens on this phone; nothing is changed unless you choose to."
    } else {
        "Add folders in the library first. Tidy up looks through the folders you chose there."
    },
) { if (hasFolders) Button(onClick = onScan) { Text("Scan folders") } }

@Composable
private fun Results(report: TidyReport, onOpen: (TidyFile) -> Unit, onShare: (TidyFile) -> Unit, onDelete: ((TidyFile) -> Unit)?) {
    var section by rememberSaveable { mutableStateOf(TidySection.All) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val counts = mapOf(
        TidySection.Identical to report.identical.size,
        TidySection.Documents to report.similarDocuments.size,
        TidySection.Photos to report.similarPhotos.size,
        TidySection.Collections to report.collections.size,
    )
    fun shows(s: TidySection) = (section == TidySection.All || section == s) && (counts[s] ?: 0) > 0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "summary") { Summary(report) }
        if (!report.isEmpty) {
            item(key = "chips") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TidySection.entries.filter { it == TidySection.All || (counts[it] ?: 0) > 0 }, key = { it.name }) { s ->
                        FilterChip(
                            selected = section == s,
                            onClick = { section = s },
                            label = { Text(if (s == TidySection.All) s.label else "${s.label} ${counts[s]}") },
                        )
                    }
                }
            }
        }
        if (shows(TidySection.Identical)) {
            title("identical", "Identical files")
            items(report.identical, key = { "i:" + it.files.first().identity }) { IdenticalCard(it, onOpen, onShare, onDelete) }
        }
        if (shows(TidySection.Documents)) {
            title("documents", "Similar documents")
            items(report.similarDocuments, key = { "d:" + it.files.first().identity }) { SimilarCard("${it.files.size} versions of one document", it, onOpen, onShare) }
        }
        if (shows(TidySection.Photos)) {
            title("photos", "Similar photos")
            items(report.similarPhotos, key = { "p:" + it.files.first().identity }) { SimilarCard("${it.files.size} photos that look alike", it, onOpen, onShare) }
        }
        if (shows(TidySection.Collections)) {
            title("collections", "Collections")
            items(report.collections, key = { "c:" + it.kind.name }) { collection ->
                CollectionCard(
                    collection,
                    expanded = expanded[collection.kind.name] == true,
                    onToggle = { expanded[collection.kind.name] = expanded[collection.kind.name] != true },
                    onOpen = onOpen,
                    onShare = onShare,
                )
            }
        }
    }
}

private fun LazyListScope.title(key: String, text: String) {
    item(key = "t:$key") { EzSectionHeader(text) }
}

@Composable
private fun Summary(report: TidyReport) {
    Column(Modifier.fillMaxWidth().glassCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            report.isEmpty -> {
                Text("All tidy", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
                Text("No copies, look-alikes or collections turned up.", color = EzColors.TextSecondary)
            }
            report.reclaimableBytes > 0 -> {
                Text("${formatBytes(report.reclaimableBytes)} to free", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
                Text("by keeping one of each identical file", color = EzColors.TextSecondary)
            }
            else -> Text("Here's what turned up", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
        }
        Text("${report.fileCount} files looked at · ${formatDate(report.scannedAt)}", fontSize = 12.sp, color = EzColors.TextSecondary)
    }
}

@Composable
private fun IdenticalCard(group: IdenticalGroup, onOpen: (TidyFile) -> Unit, onShare: (TidyFile) -> Unit, onDelete: ((TidyFile) -> Unit)?) =
    GroupCard("${group.files.size} identical copies", "${formatBytes(group.sizeBytes)} each · ${formatBytes(group.reclaimableBytes)} to free") {
        group.files.forEachIndexed { index, file ->
            FileRow(file, if (index == 0) "Kept copy · ${meta(file)}" else meta(file), onOpen, onShare, onDelete.takeIf { index > 0 })
        }
    }

@Composable
private fun SimilarCard(title: String, group: SimilarGroup, onOpen: (TidyFile) -> Unit, onShare: (TidyFile) -> Unit) =
    GroupCard(title, null) { group.files.forEach { FileRow(it, meta(it), onOpen, onShare, null) } }

@Composable
private fun CollectionCard(collection: TidyCollection, expanded: Boolean, onToggle: () -> Unit, onOpen: (TidyFile) -> Unit, onShare: (TidyFile) -> Unit) {
    val shown = if (expanded) collection.entries else collection.entries.take(COLLAPSED_ENTRIES)
    GroupCard(collection.kind.title, if (collection.entries.size == 1) "1 file" else "${collection.entries.size} files") {
        shown.forEach { entry -> FileRow(entry.file, entry.reason.describe(), onOpen, onShare, null) }
        if (collection.entries.size > COLLAPSED_ENTRIES) {
            TextButton(onClick = onToggle, modifier = Modifier.padding(start = 8.dp)) {
                Text(if (expanded) "Show fewer" else "Show all ${collection.entries.size}")
            }
        }
    }
}

@Composable
private fun GroupCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard().padding(vertical = 10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = EzColors.TextSecondary)
        }
        content()
    }
}

/** Tap to open; the menu offers Delete on extra identical copies only. */
@Composable
private fun FileRow(file: TidyFile, meta: String, onOpen: (TidyFile) -> Unit, onShare: (TidyFile) -> Unit, onDelete: ((TidyFile) -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = "Open") { onOpen(file) }
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(EzIcons.Document, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(file.name, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, fontSize = 12.sp, color = EzColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        run {
            Box {
                IconButton(onClick = { menu = true }) { Icon(EzIcons.More, "More options for ${file.name}", tint = EzColors.TextSecondary) }
                EzGlassDropdown(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(EzIcons.Share, null) },
                        onClick = {
                            menu = false
                            onShare(file)
                        },
                    )
                    if (onDelete != null) DropdownMenuItem(
                        text = { Text("Delete…", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(EzIcons.Delete, null) },
                        onClick = {
                            menu = false
                            onDelete(file)
                        },
                    )
                }
            }
        }
    }
}

private fun meta(file: TidyFile) = listOfNotNull(file.sizeBytes?.let(::formatBytes), file.modifiedAt?.let(::formatDate)).joinToString(" · ")

private fun formatDate(epochMillis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private const val COLLAPSED_ENTRIES = 4
