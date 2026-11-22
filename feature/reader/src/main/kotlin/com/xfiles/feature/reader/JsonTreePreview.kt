package com.xfiles.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.glassPanel
import com.xfiles.design.rememberHaptics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A JSON document as a tree; `null` when [text] is not valid JSON (the caller then shows plain text). */
internal fun parseJsonOrNull(text: String): JsonElement? =
    if (text.length > MAX_JSON_CHARS) null else runCatching { Json.parseToJsonElement(text) }.getOrNull()

/** How a JSON file is shown: its tree, or its text pretty-printed, minified, or exactly as stored. */
internal enum class JsonView(val label: String) {
    Tree("Tree"),
    Pretty("Pretty"),
    Minified("Minified"),
    Raw("Raw"),
}

/**
 * JSON in one of four views. The tree is a lazy list of rows, so a huge collapsed array costs nothing;
 * every view fills the viewport and scrolls over its whole area, not only over the text.
 */
@Composable
internal fun JsonTreePreview(root: JsonElement, raw: String) {
    val haptics = rememberHaptics()
    var view by rememberSaveable { mutableStateOf(JsonView.Tree) }
    // The expanded paths are an immutable set held in state, so every change is a new value to draw from.
    var expanded by remember(root) { mutableStateOf(setOf("$")) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JsonView.entries.forEach { option ->
                    FilterChip(
                        selected = view == option,
                        onClick = {
                            if (view != option) {
                                view = option
                                haptics.segment()
                            }
                        },
                        label = { Text(option.label) },
                    )
                }
            }
            if (view == JsonView.Tree) {
                TextButton(onClick = {
                    expanded = jsonContainerPaths(root)
                    haptics.confirm()
                }) { Text("Expand all") }
                TextButton(onClick = {
                    expanded = setOf("$")
                    haptics.confirm()
                }) { Text("Collapse all") }
            }
        }
        when (view) {
            JsonView.Tree -> JsonTree(root, expanded, onToggle = { path ->
                expanded = if (path in expanded) expanded - path else expanded + path
                haptics.tick()
            })
            JsonView.Pretty -> JsonText(remember(root) { prettyJson(root) })
            JsonView.Minified -> JsonText(remember(root) { root.toString() })
            JsonView.Raw -> JsonText(raw)
        }
    }
}

@Composable
private fun JsonTree(root: JsonElement, expanded: Set<String>, onToggle: (String) -> Unit) {
    val rows = remember(root, expanded) { jsonRows(root, expanded).take(MAX_ROWS).toList() }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
    Box(Modifier.fillMaxSize().glassPanel(RoundedCornerShape(16.dp), elevation = 3.dp)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            items(rows, key = { it.path }) { row ->
                val container = row.value is JsonObject || row.value is JsonArray
                Text(
                    buildAnnotatedString {
                        append("  ".repeat(row.depth))
                        if (container) append(if (row.path in expanded) "▾ " else "▸ ") else append("  ")
                        row.key?.let { withStyle(SpanStyle(color = KEY)) { append(it) }; append(": ") }
                        when (val value = row.value) {
                            is JsonObject -> withStyle(SpanStyle(color = EzColors.TextSecondary)) { append("{ ${value.size} ${if (value.size == 1) "key" else "keys"} }") }
                            is JsonArray -> withStyle(SpanStyle(color = EzColors.TextSecondary)) { append("[ ${value.size} ${if (value.size == 1) "item" else "items"} ]") }
                            JsonNull -> withStyle(SpanStyle(color = LITERAL)) { append("null") }
                            is JsonPrimitive -> withStyle(SpanStyle(color = if (value.isString) STRING else LITERAL)) {
                                append(if (value.isString) "\"${value.content}\"" else value.content)
                            }
                        }
                    },
                    Modifier.fillMaxWidth()
                        .then(if (container) Modifier.clickable(role = Role.Button) { onToggle(row.path) } else Modifier)
                        .padding(vertical = 2.dp),
                    style = mono,
                    color = EzColors.Text,
                )
            }
            if (rows.size == MAX_ROWS) item { Text("Showing the first $MAX_ROWS rows. Collapse parts to see others.", Modifier.padding(top = 8.dp)) }
        }
    }
}

/** Pretty, minified, or raw text: scrolls vertically and horizontally over the whole panel. */
@Composable
private fun JsonText(text: String) {
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Box(
        Modifier.fillMaxSize()
            .glassPanel(RoundedCornerShape(16.dp), elevation = 3.dp)
            .verticalScroll(vertical)
            .horizontalScroll(horizontal)
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(text, style = mono, color = MaterialTheme.colorScheme.onSurface, softWrap = false)
        }
    }
}

private fun prettyJson(root: JsonElement): String =
    Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), root)

/** Every path that is an object or array, so "Expand all" opens the whole tree (bounded by [MAX_ROWS]). */
private fun jsonContainerPaths(root: JsonElement): Set<String> {
    val paths = HashSet<String>()
    fun walk(path: String, value: JsonElement) {
        if (paths.size >= MAX_ROWS) return
        when (value) {
            is JsonObject -> {
                paths.add(path)
                value.forEach { (name, child) -> walk("$path.$name", child) }
            }
            is JsonArray -> {
                paths.add(path)
                value.forEachIndexed { index, child -> walk("$path[$index]", child) }
            }
            else -> Unit
        }
    }
    walk("$", root)
    return paths
}

private class JsonRow(val path: String, val key: String?, val value: JsonElement, val depth: Int)

private fun jsonRows(root: JsonElement, expanded: Set<String>): Sequence<JsonRow> = sequence {
    suspend fun SequenceScope<JsonRow>.visit(path: String, key: String?, value: JsonElement, depth: Int) {
        yield(JsonRow(path, key, value, depth))
        if (path !in expanded) return
        when (value) {
            is JsonObject -> value.forEach { (name, child) -> visit("$path.$name", name, child, depth + 1) }
            is JsonArray -> value.forEachIndexed { index, child -> visit("$path[$index]", "$index", child, depth + 1) }
            else -> Unit
        }
    }
    visit("$", null, root, 0)
}

private val KEY = Color(0xFF7C4DFF)
private val STRING = Color(0xFF2E7D32)
private val LITERAL = Color(0xFF1565C0)
private const val MAX_ROWS = 20_000
private const val MAX_JSON_CHARS = 4_000_000
