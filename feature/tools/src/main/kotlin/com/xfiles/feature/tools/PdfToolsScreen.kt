package com.xfiles.feature.tools

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import com.xfiles.design.glassCard
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
private fun ToolsHeader(onBack: () -> Unit, onNewNote: () -> Unit, onTidy: () -> Unit, section: Int, onSection: (Int) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(EzIcons.Back, "Back to library") }
            Text("Tools", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onTidy, enabled = enabled) { Text("Tidy up") }
            TextButton(onClick = onNewNote, enabled = enabled) { Text("New note") }
        }
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
