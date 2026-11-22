package com.xfiles.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.FilePreview
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.glassPanel

@Composable
internal fun TextPreview(
    preview: FilePreview.Text,
    showSearch: Boolean,
    onSearchClose: () -> Unit,
) {
    val content = preview.content
    val palette = LocalPageStyle.current.colors()
    val scale = LocalTextScale.current
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale)
    val sections = remember(content) { textSections(content) }
    val search = rememberTextFieldState()
    val query = if (showSearch) search.text.toString() else ""
    val matches = remember(content, query) { textMatches(content, query) }
    var current by remember(content, query) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    RegisterViewerScroll(list) { LazyScroll(list) }
    val selected = matches.getOrNull(current)
    LaunchedEffect(selected) {
        if (selected != null) {
            val index = sections.binarySearch { it.first - selected.first }
            val section = if (index >= 0) index else -index - 2
            list.scrollToItem(section.coerceAtLeast(0) + if (preview.truncated) 1 else 0)
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showSearch) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    state = search,
                    modifier = Modifier.weight(1f),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    label = { Text("Find in file") },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Text(
                                if (matches.isEmpty()) "0 / 0" else "${current + 1} / ${matches.size}${if (matches.size == MAX_HITS) "+" else ""}",
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    },
                )
                IconButton(onClick = { current-- }, enabled = current > 0) {
                    Icon(EzIcons.ChevronUp, "Previous result")
                }
                IconButton(onClick = { current++ }, enabled = current + 1 < matches.size) {
                    Icon(EzIcons.ChevronDown, "Next result")
                }
                IconButton(onClick = onSearchClose) { Icon(EzIcons.Close, "Close search") }
            }
        }
        val pageModifier = if (palette == null) {
            Modifier.fillMaxSize().glassPanel(RoundedCornerShape(16.dp), elevation = 3.dp)
        } else {
            Modifier.fillMaxSize().background(palette.page, RoundedCornerShape(16.dp))
        }
        Box(pageModifier) {
            LazyColumn(state = list, contentPadding = PaddingValues(16.dp)) {
                if (preview.truncated) item { Text("Showing the beginning of this file.", Modifier.padding(bottom = 12.dp), color = palette?.secondary ?: Color.Unspecified) }
                if (sections.isEmpty()) item { Text("This file has no readable text.", color = palette?.secondary ?: Color.Unspecified) }
                itemsIndexed(sections, key = { _, section -> section.first }) { _, section ->
                    val selectedColor = EzColors.Selected
                    val otherColor = selectedColor.copy(alpha = 0.5f)
                    val styled = remember(content, section, matches, selected, selectedColor, otherColor) {
                        buildAnnotatedString {
                            append(content.substring(section.first, section.last + 1))
                            val start = matches.binarySearch { it.last.compareTo(section.first) }
                            val first = if (start >= 0) start else -start - 1
                            for (index in first until matches.size) {
                                val match = matches[index]
                                if (match.first > section.last) break
                                val from = maxOf(section.first, match.first) - section.first
                                val to = minOf(section.last, match.last) - section.first + 1
                                addStyle(SpanStyle(background = if (match == selected) selectedColor else otherColor), from, to)
                            }
                        }
                    }
                    SelectionContainer {
                        Text(styled, style = bodyStyle, color = palette?.text ?: Color.Unspecified)
                    }
                }
            }
        }
    }
}

private const val MAX_HITS = 5_000

private fun textMatches(content: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    return buildList {
        var start = 0
        while (size < MAX_HITS) {
            val offset = content.indexOf(query, start, ignoreCase = true)
            if (offset < 0) break
            start = offset + query.length
            add(offset until start)
        }
    }
}

private fun textSections(content: String): List<IntRange> = buildList {
    var start = 0
    while (start < content.length) {
        val limit = minOf(start + 1_024, content.length)
        var end = if (limit == content.length) limit else content.lastIndexOf('\n', limit - 1) + 1
        if (end <= start) end = limit
        if (end < content.length && content[end - 1].isHighSurrogate()) end--
        add(start until end)
        start = end
    }
}
