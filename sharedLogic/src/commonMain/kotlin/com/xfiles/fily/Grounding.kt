package com.xfiles.fily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks a content answer against the text the tools actually returned, before Fily speaks it.
 * A small model can invent amounts, dates or facts; an answer whose numbers or key words are not
 * in the evidence is replaced by an honest "couldn't find it". Grounded answers get the page
 * they came from, when the evidence is labelled "[page N]".
 */
object Grounding {
    /** Tools whose results are document text, so a following reply must be grounded in them. */
    val CONTENT_TOOLS = setOf("getDocumentExcerpt", "findTextInDocument")

    const val NOT_FOUND = "I couldn't find that in the file."

    private const val MIN_SUPPORT = 0.5
    private const val MIN_WORDS_TO_JUDGE = 2
    private val NUMBER = Regex("""\d[\d,.:/-]*\d|\d""")
    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val PAGE_LABEL = Regex("""\[page (\d+)]""")
    private val PAGE_MENTION = Regex("""\bpages?\s+\d+""", RegexOption.IGNORE_CASE)
    private val ALREADY_NEGATIVE = Regex(
        """couldn'?t find|could not find|can'?t find|not (in|mentioned)|no mention|doesn'?t (say|mention)|isn'?t (in|there)|not sure""",
        RegexOption.IGNORE_CASE,
    )

    /** Every string inside a tool result, so JSON keys and escapes don't count as evidence. */
    fun evidenceOf(toolResult: String): String = runCatching {
        buildString { collect(Json.parseToJsonElement(toolResult), this) }
    }.getOrDefault(toolResult)

    private fun collect(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonPrimitive -> if (element.isString) out.append(element.content).append('\n')
            is JsonArray -> element.forEach { collect(it, out) }
            is JsonObject -> element.values.forEach { collect(it, out) }
        }
    }

    /** The reply to speak: [reply] itself (plus a page) when grounded in [evidence], else [NOT_FOUND]. */
    fun check(reply: String, evidence: String): String {
        if (evidence.isBlank() || ALREADY_NEGATIVE.containsMatchIn(reply)) return reply
        val haystack = evidence.lowercase()
        val digits = haystack.filter(Char::isLetterOrDigit)
        // Numbers are where a small model hallucinates most: each one must be in the evidence.
        val numbers = NUMBER.findAll(reply.replace(PAGE_MENTION, "")).map { it.value.filter(Char::isDigit) }.toList()
        if (numbers.any { it !in digits }) return NOT_FOUND
        val words = keyWords(reply)
        if (words.size >= MIN_WORDS_TO_JUDGE) {
            val known = keyWords(evidence).mapTo(HashSet(), ::stem)
            val supported = words.count { stem(it) in known }
            if (supported.toDouble() / words.size < MIN_SUPPORT) return NOT_FOUND
        }
        if (PAGE_MENTION.containsMatchIn(reply)) return reply
        val page = bestPage(words, evidence) ?: return reply
        return reply.trimEnd().trimEnd('.') + " (page $page)."
    }

    /** The labelled page whose text shares the most key words with the answer. */
    private fun bestPage(words: List<String>, evidence: String): Int? {
        val labels = PAGE_LABEL.findAll(evidence).toList()
        if (labels.isEmpty()) return null
        val stems = words.mapTo(HashSet(), ::stem)
        return labels.mapIndexed { i, label ->
            val end = labels.getOrNull(i + 1)?.range?.first ?: evidence.length
            val text = evidence.substring(label.range.last + 1, end)
            label.groupValues[1].toInt() to keyWords(text).count { stem(it) in stems }
        }.maxByOrNull { it.second }?.takeIf { it.second > 0 || labels.size == 1 }?.first
    }

    private fun keyWords(text: String): List<String> = WORD.findAll(text.lowercase())
        .map { it.value }
        .filter { it.length >= 4 && it !in STOP && !it.all(Char::isDigit) }
        .distinct()
        .toList()

    /** A crude stem: enough to match "invoices" with "invoice" and "payment" with "payments". */
    private fun stem(word: String): String = word.take(6)

    private val STOP = setOf(
        "this", "that", "with", "from", "have", "your", "file", "document", "there", "their", "which",
        "what", "when", "about", "says", "said", "they", "will", "would", "should", "could", "been",
        "were", "into", "also", "only", "just", "some", "more", "than", "then", "here", "page", "pages",
    )
}
