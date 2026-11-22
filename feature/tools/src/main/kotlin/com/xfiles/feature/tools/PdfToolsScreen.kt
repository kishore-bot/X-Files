package com.xfiles.feature.tools

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.core.documents.PdfAction
import com.xfiles.core.documents.PdfPaper
import com.xfiles.core.documents.PdfToolOptions
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzTextButton
import com.xfiles.design.EzTopBar
import com.xfiles.design.HapticOnChange
import com.xfiles.design.glassBar
import com.xfiles.design.glassCard
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/** The single-PDF tools that have their own screens, opened from the Edit PDFs tiles. */
internal enum class PdfDedicatedTool(val title: String, val detail: String) {
    Reorder("Reorder", "Drag, turn or remove pages"),
    Split("Split", "Page ranges into separate files"),
    Compress("Compress", "Smaller file, same pages"),
    Watermark("Watermark", "Text over the pages"),
    Sign("Sign", "Draw and place a signature"),
}

@Composable
internal fun DedicatedToolTiles(onOpen: (PdfDedicatedTool) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PdfDedicatedTool.entries.forEach { tool ->
            Column(
                Modifier
                    .width(136.dp)
                    .glassCard()
                    .clickable(enabled = enabled) { onOpen(tool) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(EzIcons.PdfFile, null, tint = MaterialTheme.colorScheme.primary)
                Text(tool.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    tool.detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun DedicatedToolHeader(title: String, onBack: () -> Unit) {
    EzTopBar(title, onBack, backLabel = "Back to PDF tools", gutter = 0.dp)
}

/** Shows the chosen PDF, or asks for its password when it is protected. */
@Composable
internal fun PdfSourceCard(
    frame: PdfToolFrame,
    onChoose: () -> Unit,
    onUnlock: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().glassCard().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            frame.isOpen -> {
                Text(frame.name.ifBlank { "PDF" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append("${frame.pageCount} ${if (frame.pageCount == 1) "page" else "pages"}")
                        if (frame.signed) append(" · signed")
                        if (frame.archival) append(" · archival")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            frame.opening -> Text("Opening…")
            else -> Text("Choose a PDF to start.")
        }
        if (frame.needsPassword) {
            var password by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !frame.busy,
                label = { Text("PDF password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Button(onClick = { onUnlock(password) }, enabled = !frame.busy && password.isNotEmpty()) { Text("Unlock") }
        }
        Button(onClick = onChoose, enabled = !frame.busy) { Text(if (frame.isOpen) "Choose another PDF" else "Choose a PDF") }
    }
}

/** Progress for a running job, with cancel. Indeterminate until the engine reports a count. */
@Composable
internal fun PdfBusyBar(frame: PdfToolFrame, onCancel: () -> Unit) {
    if (!frame.busy) return
    val fraction = frame.progress
    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    if (frame.running) TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
internal fun PdfMessages(frame: PdfToolFrame) {
    frame.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (frame.saved) Text("Saved. The original PDF is unchanged.", color = MaterialTheme.colorScheme.primary)
    // Every protected result says what became of its protection, whichever tool made it.
    frame.outputs.singleOrNull()?.protectionNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Bytes as the user reads them: 900 B, 412 KB, 4.2 MB. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> "${(bytes + 500) / 1_000} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
}

@Composable
private fun ToolsHeader(onBack: () -> Unit, onNewNote: () -> Unit, onTidy: () -> Unit, section: Int, onSection: (Int) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EzTopBar("Tools", onBack, backLabel = "Back to library", gutter = 0.dp, actions = {
            EzTextButton("Tidy up", onTidy, enabled = enabled, color = EzColors.Text)
            EzTextButton("New note", onNewNote, enabled = enabled, color = EzColors.Text)
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Edit PDFs", "Create & convert").forEachIndexed { index, label ->
                FilterChip(selected = section == index, onClick = { onSection(index) }, enabled = enabled, label = { Text(label) })
            }
        }
    }
}

@Composable
fun PdfToolsScreen(
    viewModel: PdfToolsViewModel,
    onBack: () -> Unit,
    onNewNote: () -> Unit,
    onTidy: () -> Unit = {},
    convert: ConvertViewModel = hiltViewModel(),
) {
    var dedicated by rememberSaveable { mutableStateOf<PdfDedicatedTool?>(null) }
    val openTool = dedicated
    if (openTool != null) {
        val close = { dedicated = null }
        when (openTool) {
            PdfDedicatedTool.Reorder -> PdfReorderScreen(onBack = close)
            PdfDedicatedTool.Split -> PdfSplitScreen(onBack = close)
            PdfDedicatedTool.Compress -> PdfCompressScreen(onBack = close)
            PdfDedicatedTool.Watermark -> PdfWatermarkScreen(onBack = close)
            PdfDedicatedTool.Sign -> PdfSignScreen(onBack = close)
        }
        return
    }
    val section = if (viewModel.state.collectAsStateWithLifecycle().value.convert) 1 else 0
    if (section == 1) {
        val back = {
            convert.close()
            onBack()
        }
        BackHandler(onBack = back)
        Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolsHeader(onBack = back, onNewNote = onNewNote, onTidy = onTidy, section = section, onSection = { viewModel.showConvert(it == 1) }, enabled = !convert.state.collectAsStateWithLifecycle().value.busy)
            ConvertTools(convert, Modifier.weight(1f))
        }
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    HapticOnChange(state.output) { if (it != null) confirm() }
    HapticOnChange(state.error) { if (it != null) reject() }
    val input = key(state.action) { rememberTextFieldState() }
    val sourcePassword = remember(state.action, state.inputs) { TextFieldState() }
    val newPassword = remember(state.action, state.inputs) { TextFieldState() }
    var rotation by rememberSaveable { mutableIntStateOf(90) }
    var paper by rememberSaveable { mutableStateOf(PdfPaper.A4) }
    val chooseSeveral = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), viewModel::pick)
    val chooseOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.pick(listOf(uri))
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.save(uri)
    }
    val back = {
        if (!state.saving) {
            viewModel.close()
            onBack()
        }
    }
    BackHandler(onBack = back)

    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolsHeader(onBack = back, onNewNote = onNewNote, onTidy = onTidy, section = section, onSection = { viewModel.showConvert(it == 1) }, enabled = !state.busy)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PdfAction.entries.forEach { action ->
                FilterChip(
                    selected = state.action == action,
                    onClick = { viewModel.selectAction(action) },
                    enabled = !state.busy,
                    label = { Text(action.label) },
                )
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DedicatedToolTiles(onOpen = { dedicated = it }, enabled = !state.busy) }
            item {
                Button(
                    onClick = {
                        val mimeTypes = arrayOf("application/pdf")
                        if (state.action.inputCount.last > 1) chooseSeveral.launch(mimeTypes) else chooseOne.launch(mimeTypes)
                    },
                    enabled = !state.busy,
                ) { Text(if (state.action.inputCount.last > 1) "Choose PDFs" else "Choose a PDF") }
            }
            itemsIndexed(state.inputs, key = { _, item -> item.uri.toString() }) { index, file ->
                Row(
                    Modifier.fillMaxWidth().glassCard().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(EzIcons.PdfFile, null, tint = MaterialTheme.colorScheme.primary)
                    Text(file.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.action.inputCount.last > 1 && state.output == null) {
                        IconButton(onClick = { viewModel.move(index, index - 1) }, enabled = index > 0 && !state.busy) {
                            Icon(EzIcons.ChevronUp, "Move ${file.name} up")
                        }
                        IconButton(onClick = { viewModel.move(index, index + 1) }, enabled = index < state.inputs.lastIndex && !state.busy) {
                            Icon(EzIcons.ChevronDown, "Move ${file.name} down")
                        }
                    }
                }
            }
            if (state.inputs.isNotEmpty() && state.output == null) {
                when (state.action) {
                    PdfAction.Extract, PdfAction.Delete, PdfAction.Rotate, PdfAction.Insert -> item {
                        val insert = state.action == PdfAction.Insert
                        OutlinedTextField(
                            state = input,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            label = { Text(if (insert) "Insert before page" else "Pages") },
                            placeholder = { Text(if (insert) "1" else "1, 3-5") },
                            supportingText = when (state.action) {
                                PdfAction.Rotate -> ({ Text("Leave blank to rotate all pages.") })
                                PdfAction.Insert -> ({ Text("List the base PDF first and the inserted PDF second.") })
                                else -> null
                            },
                        )
                    }
                    else -> Unit
                }
                if (state.action == PdfAction.Rotate) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(90, 180, 270).forEach { angle ->
                                FilterChip(
                                    selected = angle == rotation,
                                    onClick = { rotation = angle },
                                    enabled = !state.busy,
                                    label = { Text("$angle\u00B0") },
                                )
                            }
                        }
                    }
                }
                if (state.action == PdfAction.Resize) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PdfPaper.entries.forEach { size ->
                                FilterChip(
                                    selected = size == paper,
                                    onClick = { paper = size },
                                    enabled = !state.busy,
                                    label = { Text(size.label) },
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedSecureTextField(
                        state = sourcePassword,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        label = { Text(if (state.inputs.size > 1) "First PDF password, if needed" else "Source password, if needed") },
                    )
                }
                if (state.action == PdfAction.Protect) {
                    item {
                        OutlinedSecureTextField(
                            state = newPassword,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                            label = { Text("New password") },
                        )
                    }
                }
            }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (state.saved) item { Text("PDF saved.") }
            state.output?.let { output ->
                item {
                    Text("Ready to save \u00B7 ${output.pages} pages")
                    if (output.signedSource) Text("The new copy does not carry the original digital signature.")
                    if (output.archivalSource) Text("The new copy may no longer meet PDF/A archival requirements.")
                    if (output.droppedMetadata) {
                        Text("Some links, forms, or other document metadata could not be carried into the new PDF.")
                    }
                }
            }
        }
        if (state.busy) {
            val fraction = state.progress
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            if (state.running) TextButton(onClick = viewModel::cancel) { Text("Cancel") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val output = state.output
            if (output != null) {
                Button(
                    onClick = { save.launch(state.action.outputName) },
                    enabled = !state.busy,
                ) { Text("Save PDF") }
            } else if (state.inputs.isNotEmpty()) {
                Button(
                    onClick = {
                        viewModel.prepare(
                            PdfToolOptions(
                                input.text.toString(),
                                rotation,
                                paper,
                                sourcePassword.text.toString().takeIf { it.isNotEmpty() },
                                newPassword.text.toString(),
                            ),
                        )
                    },
                    enabled = !state.busy && state.inputs.size in state.action.inputCount && when (state.action) {
                        PdfAction.Extract, PdfAction.Delete, PdfAction.Insert -> input.text.isNotBlank()
                        PdfAction.Protect -> newPassword.text.isNotEmpty()
                        else -> true
                    },
                ) { Text(state.action.label) }
            }
        }
    }
}
