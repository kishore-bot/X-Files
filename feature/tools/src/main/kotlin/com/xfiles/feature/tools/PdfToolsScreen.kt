package com.xfiles.feature.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.core.documents.PdfAction
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzSectionHeader
import com.xfiles.design.EzShapes
import com.xfiles.design.EzTopBar
import com.xfiles.design.glass
import com.xfiles.design.glassCard
import com.xfiles.design.pressScale
import com.xfiles.design.rememberHaptics

/** The single-PDF tools that have their own screens. */
enum class PdfDedicatedTool { Reorder, Split, Compress, Watermark, Sign }

private data class HubTile(val title: String, val description: String, val icon: ImageVector, val tool: OpenTool)

private val HubSections = listOf(
    "Organize pages" to listOf(
        HubTile("Merge PDFs", "Join several PDFs", EzIcons.MergePages, OpenTool.Pdf(PdfAction.Merge)),
        HubTile("Split PDF", "Save page ranges", EzIcons.SplitPages, OpenTool.Dedicated(PdfDedicatedTool.Split)),
        HubTile("Reorder pages", "Move, rotate or delete", EzIcons.Rotate, OpenTool.Dedicated(PdfDedicatedTool.Reorder)),
        HubTile("Extract pages", "Save selected pages", EzIcons.ExtractPages, OpenTool.Pdf(PdfAction.Extract)),
        HubTile("Insert PDF pages", "Insert from another PDF", EzIcons.InsertPages, OpenTool.Pdf(PdfAction.Insert)),
        HubTile("Add pictures", "Insert images into a PDF", EzIcons.Image, OpenTool.Convert(ConvertTool.AddPictures)),
        HubTile("Page size", "Fit pages to paper", EzIcons.AspectRatio, OpenTool.Pdf(PdfAction.Resize)),
    ),
    "Finish and protect" to listOf(
        HubTile("Compress PDF", "Reduce file size", EzIcons.CompressPages, OpenTool.Dedicated(PdfDedicatedTool.Compress)),
        HubTile("Watermark", "Add text over pages", EzIcons.TextT, OpenTool.Dedicated(PdfDedicatedTool.Watermark)),
        HubTile("Sign PDF", "Draw a signature", EzIcons.Signature, OpenTool.Dedicated(PdfDedicatedTool.Sign)),
        HubTile("Add password", "Lock a PDF", EzIcons.Lock, OpenTool.Pdf(PdfAction.Protect)),
        HubTile("Remove password", "Save an unlocked copy", EzIcons.Unlock, OpenTool.Pdf(PdfAction.Unlock)),
    ),
    "Export from PDF" to listOf(
        HubTile("PDF to pictures", "Save pages as images", EzIcons.Image, OpenTool.Convert(ConvertTool.PdfToPictures)),
        HubTile("PDF to text", "Save plain text", EzIcons.Lines, OpenTool.Convert(ConvertTool.PdfToText)),
        HubTile("PDF to ZIP", "Export resources", EzIcons.Archive, OpenTool.Convert(ConvertTool.PdfToZip)),
    ),
    "Create a PDF" to listOf(
        HubTile("Pictures to PDF", "Combine selected images", EzIcons.Image, OpenTool.Convert(ConvertTool.PicturesToPdf)),
        HubTile("Text to PDF", "Write or paste text", EzIcons.NoteAdd, OpenTool.Convert(ConvertTool.TextToPdf)),
        HubTile("Scan to PDF", "Capture with the camera", EzIcons.Camera, OpenTool.Convert(ConvertTool.ScanToPdf)),
    ),
)

@Composable
private fun ToolsHub(onBack: () -> Unit, onOpen: (OpenTool) -> Unit) {
    BackHandler(onBack = onBack)
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        EzTopBar("PDF tools", onBack, backLabel = "Back to library", gutter = 0.dp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 225.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HubSections.forEach { (title, tiles) ->
                item(key = title, span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                    EzSectionHeader(title)
                }
                items(tiles, key = { it.title }, contentType = { "tile" }) { tile ->
                    ToolCell(tile) {
                        haptics.contextClick()
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
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .pressScale(interaction, 0.98f)
            .glassCard(EzShapes.Card, 0.55f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).glass(EzShapes.Small, 0.85f), contentAlignment = Alignment.Center) {
            Icon(tile.icon, null, tint = EzColors.Accent, modifier = Modifier.size(23.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tile.title, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
            Text(tile.description, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
        }
        Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

/** PDF tools and the create tools, as in EzFiles. The library's Tools sheet opens either the PDF tools hub or one
 * tool directly; a tool picked in the hub goes back to the hub, a tool opened directly goes back to the library.
 * Fily and the widget open tools through [PdfToolsViewModel.open] too.
 */
@Composable
fun PdfToolsScreen(
    viewModel: PdfToolsViewModel,
    onBack: () -> Unit,
    convert: ConvertViewModel = hiltViewModel(),
) {
    val open by viewModel.open.collectAsStateWithLifecycle()
    val toHub = { if (viewModel.openedFromHub) viewModel.open(null) else onBack() }
    when (val tool = open) {
        null -> ToolsHub(onBack) {
            if (it is OpenTool.Convert) convert.select(it.tool)
            viewModel.open(it, fromHub = true)
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
            ConvertToolScreen(viewModel = convert, onBack = back)
        }
        is OpenTool.Pdf -> PdfActionScreen(viewModel, onBack = toHub)
    }
}

/** Bytes as the user reads them: 900 B, 412 KB, 4.2 MB. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> "${(bytes + 500) / 1_000} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
}
