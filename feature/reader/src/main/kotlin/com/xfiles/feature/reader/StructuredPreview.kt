package com.xfiles.feature.reader

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.glassCard
import com.xfiles.design.glassPanel
import com.xfiles.documents.structure.ContentBlock
import androidx.compose.runtime.collectAsState
import com.xfiles.documents.structure.DocumentKind
import com.xfiles.documents.structure.DocumentFormat
import com.xfiles.documents.structure.StructuredDocument
import com.xfiles.documents.structure.TextSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalDocumentImages = staticCompositionLocalOf<Map<String, ByteArray>> { emptyMap() }
private val imageDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
internal fun StructuredPreview(document: StructuredDocument, editing: StructuredEditSession? = null) {
    val editState = (editing?.state ?: kotlinx.coroutines.flow.emptyFlow<StructuredEditState>()).collectAsState(StructuredEditState()).value
    when (document.info.kind) {
        DocumentKind.Presentation -> PresentationPreview(document, slideEditor = editing?.slideEditor)
        DocumentKind.Spreadsheet -> SpreadsheetPreview(
            document,
            freezeBySheet = editState.freeze,
            cellEditor = editing?.cellEditor,
        )
        else -> if (document.info.format == DocumentFormat.Epub) BookPreview(document) else DocumentBlocks(document)
    }
}

@Composable
private fun DocumentBlocks(document: StructuredDocument) {
    // Consecutive table rows form one table item, so a table scrolls and aligns as a single unit.
    val items = remember(document) { documentItems(document.blocks) }
    val page = Modifier.fillMaxSize().pagePanel()
    val blocksList = rememberViewerList("blocks", items.size + if (document.truncated) 1 else 0)
    RegisterViewerScroll(blocksList) { LazyScroll(blocksList) }
    Box(page, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = blocksList,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (document.truncated) item { Text("Showing the beginning of this file.", color = pageSecondary(EzColors.TextSecondary)) }
            if (document.blocks.isEmpty()) item { Text("This file has no readable content.") }
            itemsIndexed(items, key = { index, _ -> index }) { _, item ->
                when (item) {
                    is DocumentItem.Single -> DocumentBlock(item.block)
                    is DocumentItem.Table -> DocumentTable(item.rows)
                }
            }
        }
    }
}

@Composable
internal fun DocumentBlock(block: ContentBlock) {
    when (block) {
        is ContentBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }
            val dark = themeIsDark()
            val styled = remember(block, dark) { richText(block.text, block.spans, dark) }
            SelectionContainer { Text(styled, modifier = Modifier.semantics { heading() }, style = style) }
        }
        is ContentBlock.Paragraph -> RichParagraph(block.text, block.spans)
        is ContentBlock.Quote -> Box(Modifier.fillMaxWidth().pageCard().padding(12.dp)) {
            RichParagraph(block.text, block.spans, italic = true)
        }
        is ContentBlock.ListItem -> Row(
            Modifier.padding(start = (block.depth.coerceIn(0, 8) * 14).dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val marker = block.marker ?: "•"
            if (marker.isNotEmpty()) Text(marker, color = EzColors.Accent)
            RichParagraph(block.text, block.spans)
        }
        is ContentBlock.Code -> SelectionContainer {
            Text(
                remember(block) {
                    if (block.firstLine == null) block.text else block.text.lineSequence().mapIndexed { index, line ->
                        "${block.firstLine!! + index}  $line"
                    }.joinToString("\n")
                },
                modifier = Modifier.fillMaxWidth().pageCard().padding(12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is ContentBlock.Image -> {
            val images = LocalDocumentImages.current
            val image = embeddedImage(block.path?.let(images::get), 1280)
            if (image == null) {
                if (block.alt != null) Text(block.alt.orEmpty(), color = pageSecondary(EzColors.TextSecondary))
            } else {
                Image(
                    image,
                    block.alt,
                    Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        // A lone row (outside DocumentBlocks grouping) draws as a one-row table.
        is ContentBlock.TableRow -> DocumentTable(listOf(block))
        is ContentBlock.Section -> Text(block.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        is ContentBlock.Entry -> {
            val context = LocalContext.current
            Row(
                Modifier.fillMaxWidth().pageCard().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (block.isDirectory) EzIcons.Folder else EzIcons.Document, null, tint = EzColors.Primary)
                Text(block.path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (!block.isDirectory) {
                    Text(Formatter.formatShortFileSize(context, block.size), color = pageSecondary(EzColors.TextSecondary))
                }
            }
        }
        ContentBlock.Divider -> HorizontalDivider()
        else -> Unit
    }
}

@Composable
private fun RichParagraph(text: String, spans: List<TextSpan>, italic: Boolean = false) {
    val dark = themeIsDark()
    val styled = remember(text, spans, dark) { richText(text, spans, dark) }
    SelectionContainer {
        Text(
            styled,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
            fontStyle = if (italic) FontStyle.Italic else null,
        )
    }
}

/**
 * [onDark]: the text sits on a dark theme (true) or a light one (false); a colour the book set that
 * would vanish there (black ink in dark mode, white in light mode) falls back to the theme's text colour.
 * Null keeps every colour as written (slides draw their own backgrounds).
 */
internal fun richText(text: String, spans: List<TextSpan>, onDark: Boolean? = null): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(start, text.length)
        if (start == end) continue
        val decorations = buildList {
            if (span.underline || span.link != null) add(TextDecoration.Underline)
            if (span.strikethrough) add(TextDecoration.LineThrough)
        }
        addStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                textDecoration = decorations.takeIf { it.isNotEmpty() }?.let(TextDecoration::combine),
                color = span.color?.let(::Color)?.takeIf { readableOn(it, onDark) } ?: Color.Unspecified,
            ),
            start,
            end,
        )
    }
}

private fun readableOn(color: Color, onDark: Boolean?): Boolean {
    if (onDark == null) return true
    val light = color.luminance()
    return if (onDark) light > 0.18f else light < 0.6f
}

/** True when the theme's text is light, i.e. the page behind it is dark. */
@Composable
internal fun themeIsDark(): Boolean = LocalContentColor.current.luminance() > 0.5f

@Composable
internal fun embeddedImage(bytes: ByteArray?, maxDimension: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, bytes, maxDimension) {
        value = if (bytes == null) null else withContext(imageDispatcher) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@withContext null
            var sample = 1
            while (longest / sample > maxDimension && sample <= 1 shl 24) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    }
    return image
}
