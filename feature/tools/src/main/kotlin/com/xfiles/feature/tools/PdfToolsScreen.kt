package com.xfiles.feature.tools

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.xfiles.design.EzSectionPill
import com.xfiles.design.EzShapes
import com.xfiles.design.pressScale
import com.xfiles.design.rememberEzHaptics
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

/** The single-PDF tools that have their own screens. */
enum class PdfDedicatedTool { Reorder, Split, Compress, Watermark, Sign }

/** A tile of the hub: what it says, how it looks, and the tool it opens. */
private class HubTile(val title: String, val icon: ImageVector, val color: Color, val tool: OpenTool)

/** The hub, in the order people reach for the tools. Rotating or deleting pages lives in Reorder, as in EzFiles. */
private val HubSections: List<Pair<String, List<HubTile>>> = listOf(
    "Edit" to listOf(
        HubTile("Merge PDFs", EzIcons.Plus, Color(0xFFE60023), OpenTool.Pdf(PdfAction.Merge)),
        HubTile("Add pictures to PDF", EzIcons.Image, Color(0xFF8E5BD9), OpenTool.Convert(ConvertTool.AddPictures)),
        HubTile("Split", EzIcons.Lines, Color(0xFF0B7F86), OpenTool.Dedicated(PdfDedicatedTool.Split)),
        HubTile("Reorder, rotate, delete", EzIcons.Rotate, Color(0xFF0862D9), OpenTool.Dedicated(PdfDedicatedTool.Reorder)),
        HubTile("Extract pages", EzIcons.Document, Color(0xFF5E6AD2), OpenTool.Pdf(PdfAction.Extract)),
        HubTile("Insert PDF pages", EzIcons.TwoPages, Color(0xFF1F7A5C), OpenTool.Pdf(PdfAction.Insert)),
        HubTile("Page size", EzIcons.SinglePage, Color(0xFF087F42), OpenTool.Pdf(PdfAction.Resize)),
        HubTile("Compress", EzIcons.Archive, Color(0xFFB8740B), OpenTool.Dedicated(PdfDedicatedTool.Compress)),
        HubTile("Watermark", EzIcons.TextT, Color(0xFF7A5AF8), OpenTool.Dedicated(PdfDedicatedTool.Watermark)),
        HubTile("Signature", EzIcons.Edit, Color(0xFFE0570B), OpenTool.Dedicated(PdfDedicatedTool.Sign)),
        HubTile("Add password", EzIcons.Lock, Color(0xFFD9408A), OpenTool.Pdf(PdfAction.Protect)),
        HubTile("Remove password", EzIcons.Lock, Color(0xFF6B3A63), OpenTool.Pdf(PdfAction.Unlock)),
    ),
    "Convert a PDF" to listOf(
        HubTile("PDF to pictures", EzIcons.Image, Color(0xFF2E9E6B), OpenTool.Convert(ConvertTool.PdfToPictures)),
        HubTile("PDF to text", EzIcons.TextT, Color(0xFF2F3BB5), OpenTool.Convert(ConvertTool.PdfToText)),
        HubTile("PDF to ZIP", EzIcons.Archive, Color(0xFFB0124F), OpenTool.Convert(ConvertTool.PdfToZip)),
        HubTile("Picture to text", EzIcons.Search, Color(0xFF00796B), OpenTool.Convert(ConvertTool.PictureToText)),
    ),
    "Make a PDF" to listOf(
        HubTile("Pictures to PDF", EzIcons.Image, Color(0xFF7A4A2E), OpenTool.Convert(ConvertTool.PicturesToPdf)),
        HubTile("Text to PDF", EzIcons.Lines, Color(0xFF6832C1), OpenTool.Convert(ConvertTool.TextToPdf)),
        HubTile("Scan to PDF", EzIcons.Camera, Color(0xFF373C47), OpenTool.Convert(ConvertTool.ScanToPdf)),
    ),
)

@Composable
private fun ToolsHub(onBack: () -> Unit, onNewNote: () -> Unit, onTidy: () -> Unit, onOpen: (OpenTool) -> Unit) {
    BackHandler(onBack = onBack)
    val haptics = rememberEzHaptics()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        EzTopBar("Tools", onBack, backLabel = "Back to library", gutter = 0.dp, actions = {
            EzTextButton("Tidy up", onTidy, color = EzColors.Text)
            EzTextButton("New note", onNewNote, color = EzColors.Text)
        })
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HubSections.forEach { (title, tiles) ->
                item(key = title, span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                    EzSectionPill(title, Modifier.padding(start = 4.dp, top = 12.dp), compact = true)
                }
                items(tiles, key = { it.title }, contentType = { "tile" }) { tile ->
                    ToolCell(tile) {
                        haptics.activate()
                        onOpen(tile.tool)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCell(tile: HubTile, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        // Same width and at least three lines of label, so a row of tools lines up on a phone.
        Modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.97f)
            .glassCard(EzShapes.Card, 0.5f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(tile.color), contentAlignment = Alignment.Center) {
            Icon(tile.icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(tile.title, style = MaterialTheme.typography.labelMedium, color = EzColors.Text, textAlign = TextAlign.Center, minLines = 3)
    }
}

@Composable
internal fun DedicatedToolHeader(title: String, onBack: () -> Unit) {
    EzTopBar(title, onBack, backLabel = "Back to tools", gutter = 0.dp)
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

/**
 * Tools: a hub of tiles, each opening one focused tool, as in EzFiles. Back from a tool returns to the hub; back
 * from the hub leaves Tools. Fily and the widget open a tool directly through [PdfToolsViewModel.open].
 */
@Composable
fun PdfToolsScreen(
    viewModel: PdfToolsViewModel,
    onBack: () -> Unit,
    onNewNote: () -> Unit,
    onTidy: () -> Unit = {},
    convert: ConvertViewModel = hiltViewModel(),
) {
    val open by viewModel.open.collectAsStateWithLifecycle()
    val toHub = { viewModel.open(null) }
    when (val tool = open) {
        null -> ToolsHub(onBack, onNewNote, onTidy) {
            if (it is OpenTool.Convert) convert.select(it.tool)
            viewModel.open(it)
        }
        is OpenTool.Dedicated -> {
            BackHandler(onBack = toHub)
            when (tool.tool) {
                PdfDedicatedTool.Reorder -> PdfReorderScreen(onBack = toHub)
                PdfDedicatedTool.Split -> PdfSplitScreen(onBack = toHub)
                PdfDedicatedTool.Compress -> PdfCompressScreen(onBack = toHub)
                PdfDedicatedTool.Watermark -> PdfWatermarkScreen(onBack = toHub)
                PdfDedicatedTool.Sign -> PdfSignScreen(onBack = toHub)
            }
        }
        is OpenTool.Convert -> {
            val back = {
                convert.close()
                toHub()
            }
            LaunchedEffect(tool.tool) { convert.select(tool.tool) }
            BackHandler(onBack = back)
            Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DedicatedToolHeader(tool.tool.label, back)
                ConvertTools(convert, Modifier.weight(1f), picker = false)
            }
        }
        is OpenTool.Pdf -> PdfActionScreen(viewModel, onBack = toHub)
    }
}

/** One page-range PDF action (merge, extract, insert, page size, passwords) on its own screen. */
@Composable
private fun PdfActionScreen(viewModel: PdfToolsViewModel, onBack: () -> Unit) {
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
        DedicatedToolHeader(state.action.label, back)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
