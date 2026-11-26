package com.xfiles.feature.tools

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.TextPdfAlignment
import com.xfiles.core.documents.TextPdfFont
import com.xfiles.core.documents.TextPdfStyle
import com.xfiles.core.documents.TextPdfTemplate
import com.xfiles.design.EzChip
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.raised

/**
 * Text to PDF: the text, a template card per look, the paper, the alignment and the font, and the first page as it
 * will be drawn, with paging when the text runs to more pages.
 */
internal fun LazyListScope.textSections(state: ConvertState, viewModel: ConvertViewModel, text: TextFieldState) {
    val locked = state.busy
    val style = state.textStyle
    item("text") {
        OutlinedTextField(
            state = text,
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            enabled = !locked,
            label = { Text("Text") },
        )
    }
    item("templates") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Template", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextPdfTemplate.entries.forEach { template ->
                    TemplateCard(
                        template = template,
                        selected = template == style.template,
                        enabled = !locked,
                        onClick = { viewModel.setTextStyle(TextPdfStyle.of(template, style.paper)) },
                    )
                }
            }
        }
    }
    item("paper") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Page size", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
            PaperPicker(
                selected = style.paper,
                enabled = !locked,
                onSelect = { chosen -> if (chosen != null) viewModel.setTextStyle(style.copy(paper = chosen)) },
            )
        }
    }
    item("look") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Alignment", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextPdfAlignment.entries.forEach { alignment ->
                    EzChip(
                        label = alignment.label,
                        selected = alignment == style.alignment,
                        onClick = { if (!locked) viewModel.setTextStyle(style.copy(alignment = alignment)) },
                    )
                }
            }
            Text("Font", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextPdfFont.entries.forEach { font ->
                    EzChip(
                        label = font.label,
                        selected = font == style.font,
                        onClick = { if (!locked) viewModel.setTextStyle(style.copy(font = font)) },
                    )
                }
            }
        }
    }
    item("preview") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
            if (state.textOutputOutdated) Text("Make a new PDF to apply your changes.", style = MaterialTheme.typography.bodySmall)
            TextPreviewCard(state, onPage = viewModel::showPreviewPage)
        }
    }
}

/** A template as a card: a mini page in its paper and accent colours, then its name. */
@Composable
private fun TemplateCard(template: TextPdfTemplate, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ChoiceCard(
        title = template.label,
        detail = null,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.width(120.dp),
        leading = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(template.backgroundArgb))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.width(24.dp).height(3.dp).background(Color(template.accentArgb)))
                repeat(4) { line ->
                    Box(
                        Modifier
                            .fillMaxWidth(if (line == 3) 0.6f else 1f)
                            .height(2.dp)
                            .background(Color(0xFF8A8A8A).copy(alpha = 0.6f)),
                    )
                }
            }
        },
    )
}

/** The first page as the export will draw it, with paging when the text runs to more than one. */
@Composable
private fun TextPreviewCard(state: ConvertState, onPage: (Int) -> Unit) {
    val paper = state.textStyle.paper
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth(0.7f)
                .aspectRatio(paper.width / paper.height)
                .raised(EzShapes.Card)
                .clip(EzShapes.Card),
            contentAlignment = Alignment.Center,
        ) {
            val preview = state.preview
            if (preview != null) {
                Image(preview, "Preview of the page", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    state.previewError ?: "Type some text to see it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (state.previewPages > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPage(state.previewPage - 1) }, enabled = state.previewPage > 0) {
                    Icon(EzIcons.ChevronLeft, "Previous page")
                }
                Text("Page ${state.previewPage + 1} of ${state.previewPages}", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { onPage(state.previewPage + 1) }, enabled = state.previewPage < state.previewPages - 1) {
                    Icon(EzIcons.ChevronRight, "Next page")
                }
            }
        }
    }
}
