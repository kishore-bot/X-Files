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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.glassCard
import com.xfiles.documentengine.PdfCompressSummary
import com.xfiles.documents.PdfCompressQuality
import com.xfiles.documents.PdfProtectionChoice

/**
 * Compression at a chosen quality level, with the source's password kept on the result
 * when it was protected.
 */
@Composable
fun PdfCompressScreen(
    onBack: () -> Unit,
    viewModel: PdfCompressViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val protection by viewModel.protection.collectAsStateWithLifecycle()
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri.toString()
            viewModel.open(uri)
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) viewModel.save(uri)
    }
    val back = {
        if (!frame.saving) {
            viewModel.release()
            onBack()
        }
    }
    BackHandler(onBack = back)

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DedicatedToolHeader(title = "Compress", onBack = back)
        PdfSourceCard(
            frame = frame,
            onChoose = { choose.launch(arrayOf("application/pdf")) },
            onUnlock = { password -> pickedUri?.let { viewModel.open(Uri.parse(it), password) } },
        )
        if (frame.isOpen && frame.outputs.isEmpty()) {
            Text(quality.detail, style = MaterialTheme.typography.bodySmall)
            QualityPicker(selected = quality, enabled = !frame.busy, onSelect = viewModel::setQuality)
            ProtectionRow(selected = protection, enabled = !frame.busy, onSelect = viewModel::setProtection)
        }
        summary?.let { result -> CompressResult(result) }
        PdfBusyBar(frame = frame, onCancel = viewModel::cancel)
        PdfMessages(frame)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (frame.outputs.isNotEmpty()) {
                Button(onClick = { save.launch(compressedName(frame.name)) }, enabled = !frame.busy) { Text("Save PDF") }
            } else if (frame.isOpen) {
                Button(onClick = viewModel::compress, enabled = !frame.busy) { Text("Compress") }
            }
        }
    }
}

@Composable
private fun CompressResult(result: PdfCompressSummary) {
    Column(
        Modifier.fillMaxWidth().glassCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${formatBytes(result.beforeBytes)} → ${formatBytes(result.afterBytes)}",
            style = MaterialTheme.typography.headlineSmall,
        )
        val saved = result.beforeBytes - result.afterBytes
        if (saved > 0 && result.beforeBytes > 0) {
            val percent = saved * 100 / result.beforeBytes
            Text("${percent}% smaller · ${result.pages} ${if (result.pages == 1) "page" else "pages"} kept", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "This file was already compact. The new copy is no smaller, so keeping the original is the better choice.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (result.imagesRecompressed > 0) {
            val count = result.imagesRecompressed
            Text("$count ${if (count == 1) "image was" else "images were"} re-encoded at a smaller size.", style = MaterialTheme.typography.bodySmall)
        }
        if (result.duplicatesMerged > 0) {
            Text("${result.duplicatesMerged} repeated ${if (result.duplicatesMerged == 1) "stream was" else "streams were"} stored once.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QualityPicker(
    selected: PdfCompressQuality,
    enabled: Boolean,
    onSelect: (PdfCompressQuality) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PdfCompressQuality.entries.forEach { level ->
            FilterChip(
                selected = level == selected,
                onClick = { onSelect(level) },
                enabled = enabled,
                label = { Text(level.label) },
            )
        }
    }
}

/** Protection choice for a protected source: the file's own settings, or AES-256. */
@Composable
internal fun ProtectionRow(
    selected: PdfProtectionChoice,
    enabled: Boolean,
    onSelect: (PdfProtectionChoice) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Protection", style = MaterialTheme.typography.bodyMedium)
        PdfProtectionChoice.entries.forEach { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                enabled = enabled,
                label = { Text(choice.label) },
            )
        }
    }
}

private fun compressedName(name: String): String =
    name.removeSuffix(".pdf").ifBlank { "Document" } + " compressed.pdf"
