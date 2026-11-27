@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xfiles.feature.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.HapticOnChange
import com.xfiles.design.EzControlBar
import com.xfiles.design.EzOptionMenu
import com.xfiles.design.EzShapes
import com.xfiles.design.glassCard
import com.xfiles.design.rememberHaptics

@Composable
fun TextEditorScreen(state: EditorState, viewModel: TextEditorViewModel) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var finding by rememberSaveable { mutableStateOf(false) }
    var formatting by rememberSaveable { mutableStateOf(false) }
    val find = rememberTextFieldState()
    val replacement = rememberTextFieldState()
    var matches by remember { mutableStateOf<Int?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(find.text) {
        matches = null
        report = null
    }
    val back: () -> Unit = {
        when {
            state.saving -> Unit
            finding -> finding = false
            formatting -> formatting = false
            viewModel.hasChanges() -> confirmDiscard = true
            else -> viewModel.close()
        }
    }
    BackHandler(onBack = back)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.save(uri)
    }
    val haptics = rememberHaptics()
    HapticOnChange(state.saved) { if (it) confirm() }
    HapticOnChange(state.error) { if (it != null) reject() }
    Column(Modifier.fillMaxSize().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EzControlBar {
            IconButton(onClick = back, enabled = !state.saving) { Icon(EzIcons.Back, "Close editor") }
            Text(
                state.name,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Button(
                onClick = { save.launch(viewModel.suggestFileName()) },
                enabled = !state.saving && state.blocked == null,
                modifier = Modifier.padding(end = 8.dp),
            ) { Text("Save copy") }
        }
        val editable = !state.saving && state.blocked == null
        val toggleColors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Row(
            Modifier.fillMaxWidth().glassCard(EzShapes.Bar).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::undo, enabled = editable && viewModel.text.undoState.canUndo) {
                Icon(EzIcons.Undo, "Undo")
            }
            IconButton(onClick = viewModel::redo, enabled = editable && viewModel.text.undoState.canRedo) {
                Icon(EzIcons.Redo, "Redo")
            }
            IconToggleButton(
                checked = finding,
                onCheckedChange = { finding = it; formatting = false },
                enabled = editable,
                colors = toggleColors,
            ) { Icon(EzIcons.Search, "Find and replace") }
            Text(
                "${state.encoding.label} · ${state.lineEnding.label}",
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = EzColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
            IconToggleButton(
                checked = formatting,
                onCheckedChange = { formatting = it; finding = false },
                enabled = editable,
                colors = toggleColors,
            ) { Icon(EzIcons.Settings, "Text file format") }
        }
        if (finding) {
            Column(Modifier.fillMaxWidth().glassCard().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(state = find, modifier = Modifier.weight(1f), lineLimits = TextFieldLineLimits.SingleLine, label = { Text("Find") })
                    TextButton(onClick = { matches = viewModel.findNext(find.text.toString()) }) { Text("Next") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(state = replacement, modifier = Modifier.weight(1f), lineLimits = TextFieldLineLimits.SingleLine, label = { Text("Replace with") })
                    TextButton(onClick = { matches = viewModel.replaceNext(find.text.toString(), replacement.text.toString()) }, enabled = !state.saving) { Text("Replace") }
                    TextButton(onClick = {
                        val replaced = viewModel.replaceAll(find.text.toString(), replacement.text.toString())
                        matches = 0
                        report = if (replaced == 0) "No matches." else "Replaced $replaced."
                    }, enabled = !state.saving) { Text("All") }
                }
                val status = report ?: matches?.let { if (it == 0) "No matches." else "$it ${if (it == 1) "match" else "matches"}" }
                status?.let { Text(it, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (formatting) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().glassCard().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Encoding", color = EzColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    EzOptionMenu(
                        options = if (state.saving || state.blocked != null) emptyList() else TextEncoding.entries,
                        selected = state.encoding,
                        optionLabel = { it.label },
                        onSelect = viewModel::chooseEncoding,
                        placeholder = "Encoding",
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Line ending", color = EzColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    EzOptionMenu(
                        options = if (state.saving || state.blocked != null) emptyList() else LineEnding.entries,
                        selected = state.lineEnding,
                        optionLabel = { it.label },
                        onSelect = viewModel::chooseLineEnding,
                        placeholder = "Line ending",
                    )
                }
            }
        }
        state.blocked?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.saved) Text("Copy saved.", color = EzColors.TextSecondary)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BasicTextField(
            state = viewModel.text,
            modifier = Modifier.fillMaxWidth().weight(1f)
                .clip(EzShapes.Card)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, EzShapes.Card)
                .padding(16.dp),
            enabled = !state.saving && state.blocked == null,
            inputTransformation = InputTransformation.maxLengthReject(TextCodec.EDIT_CAP_BYTES),
            lineLimits = TextFieldLineLimits.MultiLine(),
            cursorBrush = SolidColor(EzColors.Primary),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, color = EzColors.Text),
            decorator = { field ->
                Box(Modifier.fillMaxWidth()) {
                    if (viewModel.text.text.isEmpty()) Text("Write a note", color = EzColors.TextSecondary)
                    field()
                }
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved changes?") },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
            confirmButton = { TextButton(onClick = viewModel::close) { Text("Discard") } },
        )
    }
}
