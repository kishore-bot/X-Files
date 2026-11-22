package com.xfiles.feature.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
import com.xfiles.design.glassCard
import com.xfiles.design.pressScale
import com.xfiles.design.rememberHaptics

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
    ),
    "Make a PDF" to listOf(
        HubTile("Pictures to PDF", EzIcons.Image, Color(0xFF7A4A2E), OpenTool.Convert(ConvertTool.PicturesToPdf)),
        HubTile("Text to PDF", EzIcons.Lines, Color(0xFF6832C1), OpenTool.Convert(ConvertTool.TextToPdf)),
        HubTile("Scan to PDF", EzIcons.Camera, Color(0xFF373C47), OpenTool.Convert(ConvertTool.ScanToPdf)),
    ),
)

@Composable
private fun ToolsHub(onBack: () -> Unit, onOpen: (OpenTool) -> Unit) {
    BackHandler(onBack = onBack)
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        EzTopBar("PDF tools", onBack, backLabel = "Back to library", gutter = 0.dp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
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

/** PDF tools and the create tools, as in EzFiles. The library's Tools sheet opens either the PDF tools grid or one
 * tool directly; a tool picked in the grid goes back to the grid, a tool opened directly goes back to the library.
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
