package com.xfiles.fily

/** Trusted passage metadata is supplied by the document source, independently of the file's text. */
internal data class DocumentEvidence(val documentId: String, val page: Int?, val text: String)

internal object Grounding {
    val CONTENT_TOOLS = setOf("getDocumentExcerpt", "findTextInDocument")
    const val NOT_FOUND = "I couldn't find that in the file."

    private const val MIN_SUPPORT = 0.5
    private const val MIN_WORDS_TO_JUDGE = 2
    private val NUMBER = Regex("""\d+(?:[.,:/-]\d+)*""")
    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private const val PAGE_NUMBER = """(?:\d+|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)"""
    private val PAGE_MENTION = Regex(
        """\b(?:pages?|pp?\.)\s*($PAGE_NUMBER(?:\s*(?:[,–-]|\band\b|\bto\b|\bor\b)\s*$PAGE_NUMBER)*)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PAGE_RANGE = Regex("""($PAGE_NUMBER)\s*(?:[–-]|\bto\b)\s*($PAGE_NUMBER)""", RegexOption.IGNORE_CASE)
    private val PAGE_TOKEN = Regex("""\b$PAGE_NUMBER\b""", RegexOption.IGNORE_CASE)

    fun hasPageCitation(reply: String): Boolean = PAGE_MENTION.containsMatchIn(reply)

    /** Checks lexical support, with exact page metadata for citations. */
    fun check(reply: String, evidence: List<DocumentEvidence>): String {
        val excerpts = evidence.filter { it.text.isNotBlank() }
        if (excerpts.isEmpty()) return NOT_FOUND
        if (reply == NOT_FOUND) return reply

        val mentions = PAGE_MENTION.findAll(reply).toList()
        val content = PAGE_MENTION.replace(reply, "")
        val words = keyWords(content)
        val numbers = numbers(content)
        val documents = excerpts.groupBy { it.documentId }
        val supportedDocuments = documents.values.filter { supports(it, words, numbers) }
        if (supportedDocuments.isEmpty()) return NOT_FOUND

        val pages = mentions.flatMap { mentionedPages(it.groupValues[1]) ?: return NOT_FOUND }.toSet()
        if (pages.isNotEmpty()) {
            val matchingDocuments = supportedDocuments.filter { document ->
                pages.all { page ->
                    val rows = document.filter { it.page == page }
                    rows.isNotEmpty() && supports(rows, words, numbers)
                }
            }
            return if (matchingDocuments.size == 1) reply else NOT_FOUND
        }
        val document = supportedDocuments.singleOrNull() ?: return reply
        val supportingPages = document.filter { it.page != null }.groupBy { it.page }
            .filterValues { supports(it, words, numbers) }
        val page = supportingPages.keys.singleOrNull() ?: return reply
        return reply.trimEnd().trimEnd('.') + " (page $page)."
    }

    private fun mentionedPages(text: String): List<Int>? {
        val pages = mutableListOf<Int>()
        for (range in PAGE_RANGE.findAll(text)) {
            val first = spokenNumber(range.groupValues[1]) ?: return null
            val last = spokenNumber(range.groupValues[2]) ?: return null
            if (first < 1 || last < first || last - first > 20) return null
            pages += first..last
        }
        for (token in PAGE_TOKEN.findAll(PAGE_RANGE.replace(text, ""))) {
            val page = spokenNumber(token.value)?.takeIf { it > 0 } ?: return null
            pages.add(page)
        }
        return pages.takeIf { it.isNotEmpty() }
    }

    private fun supports(excerpts: List<DocumentEvidence>, words: List<String>, requiredNumbers: Set<String>): Boolean {
        val text = excerpts.joinToString("\n") { it.text }
        if (!numbers(text).containsAll(requiredNumbers)) return false
        if (words.size < MIN_WORDS_TO_JUDGE) return true
        val known = keyWords(text).toHashSet()
        return words.count { it in known }.toDouble() / words.size >= MIN_SUPPORT
    }

    private fun numbers(text: String): Set<String> =
        NUMBER.findAll(text).map { it.value.replace(",", "") }.toSet()

    private fun keyWords(text: String): List<String> = WORD.findAll(text.lowercase())
        .map { it.value }
        .filter { it.length >= 4 && it !in STOP && !it.all(Char::isDigit) }
        .map { it.take(6) }
        .distinct()
        .toList()

    private val STOP = setOf(
        "this", "that", "with", "from", "have", "your", "file", "document", "there", "their", "which",
        "what", "when", "about", "says", "said", "they", "will", "would", "should", "could", "been",
        "were", "into", "also", "only", "just", "some", "more", "than", "then", "here", "page", "pages",
    )
}
