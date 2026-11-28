package com.xfiles.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.PageImageDpiChoices
import com.xfiles.core.documents.PageImageFormat
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.EzTextButton
import com.xfiles.design.glassCard

/** The PDF a convert tool works on: a drop-zone until one is chosen, then its name with a change control. */
internal fun LazyListScope.pdfSourceSection(
    state: ConvertState,
    viewModel: ConvertViewModel,
    launch: ConvertLaunch,
    password: TextFieldState,
) {
    item("pdf") {
        val file = state.pdf
        if (file == null) {
            FilePickCard(
                title = "Choose a PDF",
                hint = "Pick the PDF to work on",
                icon = EzIcons.PdfFile,
                tint = EzColors.Primary,
                onClick = { if (!state.busy) launch.pickPdf() },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.5f).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(EzIcons.PdfFile, contentDescription = null, tint = EzColors.Primary, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = EzColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Ready to convert", style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
                }
                EzTextButton(text = "Change", onClick = launch.pickPdf, enabled = !state.busy)
            }
        }
    }
    if (state.tool == ConvertTool.PdfToPictures || state.tool == ConvertTool.AddPictures) {
        item("pdf-password") {
            OutlinedSecureTextField(
                state = password,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                label = { Text("Password, if needed") },
            )
        }
    }
}

/** The options of the PDF-source tools: picture format and resolution, or a short note on what is saved. */
internal fun LazyListScope.pdfOutputSections(state: ConvertState, viewModel: ConvertViewModel) {
    val locked = state.busy || state.output != null
    when (state.tool) {
        ConvertTool.PdfToPictures -> {
            item("format") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Picture format", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PageImageFormat.entries.forEach { format ->
                            ChoiceCard(
                                title = format.label,
                                detail = if (format == PageImageFormat.Jpeg) "Smaller files, good for photos." else "Lossless, sharp text, larger files.",
                                selected = state.pageImageFormat == format,
                                enabled = !locked,
                                onClick = { viewModel.setPageImageFormat(format) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            item("dpi") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Resolution", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PageImageDpiChoices.forEach { dpi ->
                            ChoiceCard(
                                title = "$dpi DPI",
                                detail = when (dpi) {
                                    72 -> "Screen"
                                    150 -> "Standard"
                                    else -> "Print"
                                },
                                selected = state.pageImageDpi == dpi,
                                enabled = !locked,
                                onClick = { viewModel.setPageImageDpi(dpi) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        ConvertTool.PdfToText -> item("note") {
            Text(
                "Saves the text the PDF already holds, as a plain text file.",
                style = MaterialTheme.typography.bodySmall,
                color = EzColors.TextSecondary,
            )
        }
        ConvertTool.PdfToZip -> item("note") {
            Text(
                "Packs the PDF into a ZIP archive. Choose where to save it next.",
                style = MaterialTheme.typography.bodySmall,
                color = EzColors.TextSecondary,
            )
        }
        else -> Unit
    }
}

/** Where the pictures go in the PDF: before the page given, or at the end when blank. */
@Composable
internal fun PositionField(position: TextFieldState, enabled: Boolean) {
    OutlinedTextField(
        state = position,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        lineLimits = TextFieldLineLimits.SingleLine,
        label = { Text("Insert before page") },
        supportingText = { Text("Leave blank to add the pictures at the end.") },
    )
}
