package com.xfiles.fily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

data class ModelResponse(
    val kind: String,
    val text: String? = null,
    val toolName: String? = null,
    val toolArgs: JsonObject? = null,
)

/** The shipped 350M model was trained before the rename and sometimes still says the old app name. */
private val OLD_APP_NAME = Regex("""\bEz ?Files\b""", RegexOption.IGNORE_CASE)

fun parseModelResponse(raw: String): ModelResponse {
    val response = parseRaw(raw.trim())
    // Only spoken text: tool arguments may name a real file called "EzFiles".
    return response.copy(text = response.text?.replace(OLD_APP_NAME, "Xfiles"))
}

private fun parseRaw(trimmed: String): ModelResponse {
    if (!trimmed.startsWith("{")) return ModelResponse("reply", text = trimmed)
    return try {
        val obj = json.parseToJsonElement(trimmed).jsonObject
        val kind = obj["kind"]?.jsonPrimitive?.content ?: "reply"
        when (kind) {
            "tool" -> ModelResponse(
                kind = "tool",
                toolName = obj["name"]?.jsonPrimitive?.content,
                toolArgs = obj["arguments"]?.jsonObject,
            )
            "clarify" -> ModelResponse(
                kind = "clarify",
                text = obj["text"]?.jsonPrimitive?.content ?: "Could you say more?",
            )
            else -> ModelResponse(
                kind = "reply",
                text = obj["text"]?.jsonPrimitive?.content ?: trimmed,
            )
        }
    } catch (_: Exception) {
        ModelResponse("reply", text = trimmed)
    }
}

fun spokenTextFor(response: ModelResponse): String = when (response.kind) {
    "tool" -> "Let me check."
    "clarify" -> response.text ?: "Could you say more?"
    else -> response.text ?: "I'm not sure what to say."
}
