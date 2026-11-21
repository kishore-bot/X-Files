package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.core.documents.PdfPaper
import com.xfiles.design.EzIcons
import com.xfiles.design.glassCard

/** Create and convert tools. Stateless apart from text fields; every action goes to [ConvertViewModel]. */
@Composable
internal fun ConvertTools(viewModel: ConvertViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val text = rememberTextFieldState()
    val position = rememberTextFieldState()
    val password = remember(state.pdf) { androidx.compose.foundation.text.input.TextFieldState() }
    var scanning by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.openScanner) {
        if (state.openScanner) {
            viewModel.scannerOpened()
            scanning = true
        }
    }
    if (scanning) {
        ScanCamera(viewModel.scanProcessing, state.pictures.size, onPage = viewModel::addScan, onDone = { scanning = false })
        return
    }
    LaunchedEffect(state.draft) {
        state.draft?.let {
            text.setTextAndPlaceCursorAtEnd(it)
            viewModel.draftConsumed()
        }
    }

    val pictures = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { viewModel.addPictures(it) }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { viewModel.addPictures(listOf(it)) } }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::pickPdf) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) viewModel.run(password = password.text.toString().ifEmpty { null }, target = tree)
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.run(target = uri)
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let(viewModel::savePdf) }
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(viewModel::saveText) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConvertTool.entries.forEach { tool ->
                FilterChip(selected = state.tool == tool, onClick = { viewModel.select(tool) }, enabled = !state.busy, label = { Text(tool.label) })
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                when (state.tool.input) {
                    ToolInput.Pictures -> Button(onClick = {
                        pictures.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, enabled = !state.busy) { Text(if (state.pictures.isEmpty()) "Choose pictures" else "Add pictures") }
                    ToolInput.Camera -> Button(onClick = { scanning = true }, enabled = !state.busy) {
                        Text(if (state.pictures.isEmpty()) "Scan pages" else "Scan more pages")
                    }
                    ToolInput.Picture -> Button(onClick = {
                        picture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, enabled = !state.busy) { Text("Choose a picture") }
                    ToolInput.Pdf -> Button(onClick = { pdf.launch(arrayOf("application/pdf")) }, enabled = !state.busy) {
                        Text(if (state.pdf == null) "Choose a PDF" else "Choose another PDF")
                    }
                    ToolInput.PdfAndPictures -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pdf.launch(arrayOf("application/pdf")) }, enabled = !state.busy) {
                            Text(if (state.pdf == null) "Choose a PDF" else "Another PDF")
                        }
                        OutlinedButton(onClick = {
                            pictures.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, enabled = !state.busy) { Text(if (state.pictures.isEmpty()) "Choose pictures" else "Add pictures") }
                    }
                    ToolInput.Typing -> OutlinedTextField(
                        state = text,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        enabled = !state.busy,
                        label = { Text("Text") },
                    )
                }
            }
            if (state.tool.input in setOf(ToolInput.Pictures, ToolInput.Camera, ToolInput.Picture, ToolInput.PdfAndPictures)) {
                items(state.pictures, key = Uri::toString) { uri ->
                    Row(Modifier.fillMaxWidth().glassCard().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(EzIcons.Image, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Page ${state.pictures.indexOf(uri) + 1}", Modifier.weight(1f).padding(start = 8.dp))
                        IconButton(onClick = { viewModel.removePicture(uri) }, enabled = !state.busy) { Icon(EzIcons.Close, "Remove page ${state.pictures.indexOf(uri) + 1}") }
                    }
                }
            }
            state.pdf?.takeIf { state.tool.input == ToolInput.Pdf || state.tool.input == ToolInput.PdfAndPictures }?.let { file ->
                item {
                    Row(Modifier.fillMaxWidth().glassCard().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(EzIcons.PdfFile, null, tint = MaterialTheme.colorScheme.primary)
                        Text(file.name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.tool == ConvertTool.AddPictures) {
                    item {
                        OutlinedTextField(
                            state = position,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                            lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                            label = { Text("Insert before page") },
                            supportingText = { Text("Leave blank to add the pictures at the end.") },
                        )
                    }
                }
                if (state.tool == ConvertTool.PdfToPictures || state.tool == ConvertTool.AddPictures) {
                    item { OutlinedSecureTextField(state = password, modifier = Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text("Password, if needed") }) }
                }
            }
            if (state.tool in setOf(ConvertTool.PicturesToPdf, ConvertTool.ScanToPdf, ConvertTool.TextToPdf, ConvertTool.AddPictures)) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.tool != ConvertTool.TextToPdf) {
                            FilterChip(selected = state.paper == null, onClick = { viewModel.setPaper(null) }, enabled = !state.busy, label = { Text("Fit picture") })
                        }
                        PdfPaper.entries.forEach { size ->
                            FilterChip(selected = state.paper == size, onClick = { viewModel.setPaper(size) }, enabled = !state.busy, label = { Text(size.label) })
                        }
                    }
                }
            }
            if (state.tool == ConvertTool.PicturesToPdf || state.tool == ConvertTool.ScanToPdf) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Searchable text")
                            Text(
                                "Reads each page on this phone and adds invisible text, so it can be searched and copied.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = state.searchable, onCheckedChange = viewModel::setSearchable, enabled = !state.busy)
                    }
                }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            when (val output = state.output) {
                is ConvertOutput.Pdf -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Ready to save · ${output.pdf.pages} ${if (output.pdf.pages == 1) "page" else "pages"}")
                        output.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                is ConvertOutput.Text -> item {
                    SelectionContainer {
                        Text(output.text.take(4000), Modifier.fillMaxWidth().glassCard().padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                is ConvertOutput.Done -> item { Text(output.message) }
                null -> Unit
            }
        }
        if (state.busy) {
            val fraction = state.progress
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = viewModel::cancel) { Text("Cancel") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            when (val output = state.output) {
                is ConvertOutput.Pdf -> Button(onClick = { savePdf.launch(output.suggestedName) }, enabled = !state.busy) { Text("Save PDF") }
                is ConvertOutput.Text -> Button(onClick = { saveText.launch(output.suggestedName) }, enabled = !state.busy) { Text("Save text") }
                else -> {
                    val ready = when (state.tool.input) {
                        ToolInput.Pictures, ToolInput.Camera, ToolInput.Picture -> state.pictures.isNotEmpty()
                        ToolInput.Pdf -> state.pdf != null
                        ToolInput.PdfAndPictures -> state.pdf != null && state.pictures.isNotEmpty()
                        ToolInput.Typing -> text.text.isNotBlank()
                    }
                    if (ready) {
                        OutlinedButton(onClick = {
                            when (state.tool) {
                                ConvertTool.PdfToPictures -> folder.launch(null)
                                ConvertTool.PdfToZip -> zip.launch(state.pdf?.name?.removeSuffix(".pdf").orEmpty() + ".zip")
                                ConvertTool.AddPictures -> viewModel.run(
                                    text = position.text.toString(),
                                    password = password.text.toString().ifEmpty { null },
                                )
                                else -> viewModel.run(text = text.text.toString())
                            }
                        }, enabled = !state.busy) { Text(state.tool.label) }
                    }
                }
            }
        }
    }
}
