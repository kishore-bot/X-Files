package com.xfiles.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A JSON document as a tree; `null` when [text] is not valid JSON (the caller then shows plain text). */
internal fun parseJsonOrNull(text: String): JsonElement? =
    if (text.length > MAX_JSON_CHARS) null else runCatching { Json.parseToJsonElement(text) }.getOrNull()

/**
 * Expandable JSON: objects and arrays collapse to one line with their size. Rows are produced lazily
 * from a [Sequence] walk of the expanded part of the tree, so a huge collapsed array costs nothing.
 */
@Composable
internal fun JsonTreePreview(root: JsonElement) {
    val expanded = remember(root) { mutableStateSetOf("$") }
    val rows = remember(root, expanded.toSet()) { jsonRows(root, expanded).take(MAX_ROWS).toList() }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
    Box(Modifier.fillMaxSize().glassPanel(RoundedCornerShape(16.dp), elevation = 3.dp)) {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
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
                        .then(if (container) Modifier.clickable(role = Role.Button) { if (!expanded.remove(row.path)) expanded.add(row.path) } else Modifier)
                        .padding(vertical = 2.dp),
                    style = mono,
                    color = EzColors.Text,
                )
            }
            if (rows.size == MAX_ROWS) item { Text("Showing the first ${MAX_ROWS} rows. Collapse parts to see others.", Modifier.padding(top = 8.dp)) }
        }
    }
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
