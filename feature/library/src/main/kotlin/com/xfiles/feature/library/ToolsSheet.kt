package com.xfiles.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzListItem
import com.xfiles.design.EzSectionHeader
import com.xfiles.design.staggeredEnter

/** The library's Tools menu, as in EzFiles: PDF tools first, then the tools that make something new. */
enum class LibraryTool(val title: String, val subtitle: String, val icon: ImageVector) {
    PdfTools("PDF tools", "Merge, split, rotate, sign, add a password and more", EzIcons.PdfFile),
    ScanToPdf("Scan to PDF", "Use the camera, straighten and clean up pages", EzIcons.Camera),
    ImageToText("Image to text", "Read text from a photo or the camera, on this device", EzIcons.TextT),
    ImagesToPdf("Images to PDF", "Turn pictures into one PDF", EzIcons.Image),
    TextToPdf("Text to PDF", "Type or paste text and style it", EzIcons.Lines),
    NewText("New text file", "Write notes or Markdown", EzIcons.NoteAdd),
    TidyUp("Tidy up", "Find copies and look-alikes, gather receipts and tickets", EzIcons.Sparkles),
    CompressZip("Compress to ZIP", "Zip files and folders, with a name you choose", EzIcons.Archive),
    ExtractArchive("Extract", "Open a ZIP and save all or selected files", EzIcons.Folder),
}

/** Tools are grouped: making and converting documents first, then tidying and archives. */
private val ToolGroups: List<Pair<String, List<LibraryTool>>> = listOf(
    "Documents" to listOf(
        LibraryTool.PdfTools,
        LibraryTool.ScanToPdf,
        LibraryTool.ImageToText,
        LibraryTool.ImagesToPdf,
        LibraryTool.TextToPdf,
        LibraryTool.NewText,
    ),
    "Files" to listOf(LibraryTool.TidyUp, LibraryTool.CompressZip, LibraryTool.ExtractArchive),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolsSheet(onSelect: (LibraryTool) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Tools",
                style = MaterialTheme.typography.headlineSmall,
                color = EzColors.Text,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 0.dp).semantics { heading() },
            )
            var index = 0
            ToolGroups.forEach { (group, tools) ->
                EzSectionHeader(group, Modifier.padding(horizontal = 24.dp))
                tools.forEach { tool ->
                    EzListItem(
                        title = tool.title,
                        supporting = tool.subtitle,
                        icon = tool.icon,
                        trailing = { Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.staggeredEnter(index++).fillMaxWidth().padding(horizontal = 8.dp),
                        onClick = { onSelect(tool) },
                    )
                }
            }
        }
    }
}
