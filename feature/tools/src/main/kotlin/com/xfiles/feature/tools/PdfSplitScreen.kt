package com.xfiles.feature.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.documents.PdfSpanParse
import com.xfiles.documents.parsePdfSpans

/**
 * Ranges such as `1-3, 4-8, 9-` become one PDF each. The text is checked as it's
 * typed: bounds, order, and shared pages. Names can be changed per part, and the
 * parts are written into a folder the user picks.
 */
@Composable
fun PdfSplitScreen(
    onBack: () -> Unit,
    viewModel: PdfSplitViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val input by viewModel.tool.collectAsStateWithLifecycle()
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri.toString()
            viewModel.open(uri)
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.saveParts(uri)
    }
    val back = {
        if (!frame.saving) {
            viewModel.release()
            onBack()
        }
    }
    BackHandler(onBack = back)

    val parse = remember(input.text, frame.pageCount) { parsePdfSpans(input.text, frame.pageCount) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DedicatedToolHeader(title = "Split PDF", onBack = back)
        PdfSourceCard(
            frame = frame,
            onChoose = { choose.launch(arrayOf("application/pdf")) },
            onUnlock = { password -> pickedUri?.let { viewModel.open(Uri.parse(it), password) } },
        )
        if (frame.isOpen && frame.outputs.isEmpty()) {
            OutlinedTextField(
                value = input.text,
                onValueChange = viewModel::setRanges,
                modifier = Modifier.fillMaxWidth(),
                enabled = !frame.busy,
                singleLine = true,
                label = { Text("Page ranges") },
                placeholder = { Text("1-3, 4-8, 9-") },
                supportingText = { Text(rangeMessage(parse)) },
                isError = parse is PdfSpanParse.Invalid,
            )
            if (parse is PdfSpanParse.Valid) {
                val names = viewModel.partNames(parse.spans)
                parse.spans.forEachIndexed { index, span ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Part ${index + 1} · ${span.start + 1}–${span.end + 1}") },
                        )
                        OutlinedTextField(
                            value = names[index],
                            onValueChange = { viewModel.setName(index, it) },
                            modifier = Modifier.weight(1f),
                            enabled = !frame.busy,
                            singleLine = true,
                            label = { Text("File name") },
                        )
                    }
                }
            }
        }
        PdfBusyBar(frame = frame, onCancel = viewModel::cancel)
        PdfMessages(frame)
        if (frame.outputs.isNotEmpty()) {
            Text(
                "${frame.outputs.size} ${if (frame.outputs.size == 1) "file" else "files"} ready · " +
                    frame.outputs.sumOf { it.pages }.toString() + " pages in total",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (frame.outputs.isNotEmpty()) {
                Button(onClick = { folder.launch(null) }, enabled = !frame.busy) { Text("Save to folder") }
            } else if (frame.isOpen) {
                Button(
                    onClick = { (parse as? PdfSpanParse.Valid)?.let { viewModel.split(it.spans) } },
                    enabled = !frame.busy && parse is PdfSpanParse.Valid,
                ) { Text("Split") }
            }
        }
    }
}

private fun rangeMessage(parse: PdfSpanParse): String = when (parse) {
    is PdfSpanParse.Valid -> "${parse.spans.size} ${if (parse.spans.size == 1) "part" else "parts"} · no page is shared"
    is PdfSpanParse.Invalid -> parse.message
}
