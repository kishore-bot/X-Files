package com.xfiles.feature.reader

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.glassPanel
import com.xfiles.design.rememberHaptics
import com.xfiles.documents.code.CodeFold
import com.xfiles.documents.code.CodeFolding
import com.xfiles.documents.code.SourceCodeExtensions
import com.xfiles.documents.code.SourceCodeFileNames

/** Whether [name] is source code the code viewer should show. */
internal fun isSourceCode(name: String): Boolean {
    val lower = name.lowercase()
    return lower.substringAfterLast('.', "") in SourceCodeExtensions || lower in SourceCodeFileNames
}

/**
 * Source code with line numbers, syntax colours (CodeHighlight) and folding of blocks, comments and
 * imports (CodeFolding). The whole file is highlighted once, then each line is a slice of it, so
 * multi-line strings and comments keep their colour; only visible lines are composed. "Expand all" and
 * "Collapse all" open or fold every region at once.
 */
@Composable
internal fun CodePreview(text: String, fileName: String) {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val palette = LocalPageStyle.current.colors()
    val dark = palette?.syntaxDark ?: isSystemInDarkTheme()
    val haptics = rememberHaptics()
    val lines = remember(text) { text.split('\n') }
    val starts = remember(lines) { lines.runningFold(0) { at, line -> at + line.length + 1 } }
    val highlighted = remember(text, extension, dark) { highlightCode(extension, text, dark) ?: AnnotatedString(text) }
    val folds = remember(lines, extension) { CodeFolding.regions(lines, extension).associateBy { it.start } }
    val collapsed = remember(text) { mutableStateSetOf<Int>() }
    // Visible line indices, skipping lines inside collapsed folds; a Sequence keeps it lazy per pass.
    val visible = remember(lines, folds, collapsed.toSet()) {
        sequence {
            var line = 0
            while (line < lines.size) {
                yield(line)
                val fold = folds[line]
                line = if (fold != null && line in collapsed) fold.lastHidden + 1 else line + 1
            }
        }.toList()
    }
    val gutter = (lines.size.toString().length * 9 + 12).dp
    val scale = LocalTextScale.current
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp * scale, lineHeight = 19.sp * scale)
    val pageModifier = if (palette == null) {
        Modifier.fillMaxSize().glassPanel(RoundedCornerShape(16.dp), elevation = 3.dp)
    } else {
        Modifier.fillMaxSize().background(palette.page, RoundedCornerShape(16.dp))
    }
    Box(pageModifier) {
        Column(Modifier.fillMaxSize()) {
            if (folds.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        collapsed.clear()
                        haptics.confirm()
                    }) { Text("Expand all") }
                    TextButton(onClick = {
                        collapsed.clear()
                        collapsed.addAll(folds.keys)
                        haptics.confirm()
                    }) { Text("Collapse all") }
                }
            }
            val codeList = rememberLazyListState()
            RegisterViewerScroll(codeList) { LazyScroll(codeList) }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(state = codeList, modifier = Modifier.horizontalScroll(rememberScrollState()), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(visible, key = { it }) { index ->
                        val fold: CodeFold? = folds[index]
                        val isCollapsed = index in collapsed
                        Row(
                            if (fold == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = if (isCollapsed) "Expand" else "Fold") {
                                if (!collapsed.remove(index)) collapsed.add(index)
                                haptics.tick()
                            },
                        ) {
                            Text(
                                (if (fold == null) "  " else if (isCollapsed) "▸ " else "▾ ") + (index + 1),
                                Modifier.width(gutter + 14.dp).padding(start = 8.dp),
                                style = mono,
                                color = palette?.secondary ?: EzColors.TextSecondary,
                            )
                            val start = starts[index].coerceAtMost(highlighted.length)
                            val end = (start + lines[index].length).coerceAtMost(highlighted.length)
                            val line = highlighted.subSequence(start, end)
                            Text(
                                if (isCollapsed && fold != null) AnnotatedString(line.text + " " + CodeFolding.placeholder(fold, lines)) else line,
                                Modifier.padding(end = 16.dp),
                                style = mono,
                                color = palette?.text ?: if (dark) Color(0xFFD4D4D4) else EzColors.Text,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
