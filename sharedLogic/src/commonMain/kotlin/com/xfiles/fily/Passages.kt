package com.xfiles.fily

import kotlin.math.ln

/**
 * Picks the parts of a document that answer a question, so a small model sees the relevant
 * paragraphs instead of only the first page. Plain term scoring (BM25-style), no embeddings.
 * PDF passages are labelled "[page N]", so answers can cite real pages.
 */
internal object Passages {
    /** Small enough that a 1,500-character budget holds about five separate passages. */
    private const val CHUNK_CHARS = 280
    /** Between passages in a prompt. Three characters: the engine's budget counts it as such. */
    internal const val SEPARATOR = "\n…\n"

    private val stopWords = setOf(
        "the", "and", "for", "are", "was", "were", "what", "which", "who", "whom", "when", "where", "why", "how",
        "does", "did", "this", "that", "these", "those", "there", "with", "from", "about", "into", "say", "says",
        "said", "tell", "show", "find", "give", "please", "file", "document", "doc", "page", "pages", "text",
        "can", "you", "your", "mine", "any", "all", "much", "many", "have", "has", "had", "its", "they", "them",
        "summarize", "summary", "read", "explain", "mention", "mentioned", "fily", "hey", "okay",
    )
    private val pageRequest = Regex("""\bpage (\w+)\b""")

    /**
     * Words a question uses for what documents say differently ("my experience" vs "engineer at X,
     * 2023-present"). Related words count at [RELATED_WEIGHT] of the user's own words.
     */
    private val related = mapOf(
        "experience" to listOf("work", "worked", "role", "company", "engineer", "developer", "intern", "present", "year", "employment", "job"),
        "company" to listOf("work", "worked", "employer", "present", "current", "ltd", "inc", "pvt", "technologies", "engineer", "role"),
        "work" to listOf("experience", "company", "role", "present", "job", "engineer", "developer"),
        "job" to listOf("experience", "role", "company", "present", "position"),
        "current" to listOf("present", "now", "currently", "today"),
        "skill" to listOf("technologies", "tools", "stack", "languages", "framework", "proficient"),
        "education" to listOf("degree", "university", "college", "school", "btech", "bachelor", "master", "graduated"),
        "study" to listOf("degree", "university", "college", "education", "bachelor", "master"),
        "college" to listOf("university", "degree", "education", "institute"),
        "contact" to listOf("email", "phone", "mobile", "linkedin", "address"),
        "phone" to listOf("mobile", "contact", "tel"),
        "salary" to listOf("ctc", "pay", "compensation", "payment", "amount"),
        "payment" to listOf("fee", "amount", "total", "inr", "price", "paid", "pay", "instalment"),
        "cost" to listOf("fee", "amount", "total", "inr", "price"),
        "pay" to listOf("payment", "fee", "amount", "total", "due"),
        "due" to listOf("deadline", "date", "before", "within"),
        "deadline" to listOf("due", "date", "before", "within"),
        "terminate" to listOf("termination", "notice", "cancel", "end"),
        "end" to listOf("termination", "notice", "expire", "until"),
        "project" to listOf("built", "developed", "implemented", "designed"),
    )
    private const val RELATED_WEIGHT = 0.5
    /** The document's opening (who or what it is) is always offered when it fits in this many characters. */
    private const val OPENING_CHARS = 300

    /**
     * At most [budget] characters, in document order; a page the question names; or the start of
     * the document when nothing matches. [pageStarts] are PDF page offsets into [text], if any.
     * The second value is true when the document holds more than was returned.
     */
    fun select(text: String, question: String, budget: Int, pageStarts: List<Int> = emptyList()): Pair<List<FilyPassage>, Boolean> {
        pageText(text, pageStarts, question, budget)?.let { return it }
        val own = terms(question)
        if (text.length <= budget || own.isEmpty()) return start(text, pageStarts, budget)
        val weights = HashMap<String, Double>()
        for (term in own) for (word in related[term].orEmpty()) weights[stem(word)] = RELATED_WEIGHT
        for (term in own) weights[term] = 1.0
        val chunks = chunks(text, pageStarts)
        val words = chunks.map { chunk -> words(chunk.text).map(::stem) }
        val documentFrequency = weights.keys.associateWith { term -> words.count { term in it } }
        val scores = words.map { chunkWords ->
            weights.entries.sumOf { (term, weight) ->
                val frequency = chunkWords.count { it == term }
                if (frequency == 0) 0.0
                else weight * ln(1.0 + chunks.size.toDouble() / documentFrequency.getValue(term)) * frequency / (frequency + 1.2)
            }
        }
        val ranked = chunks.indices.filter { scores[it] > 0.0 }.sortedByDescending { scores[it] }
        if (ranked.isEmpty()) return start(text, pageStarts, budget).first to true
        val chosen = ArrayList<Int>()
        var used = 0
        for (index in ranked) {
            val cost = labelled(chunks[index]).length + if (chosen.isEmpty()) 0 else SEPARATOR.length
            if (used + cost > budget) continue
            chosen += index
            used += cost
        }
        if (chosen.isEmpty()) {
            chosen += ranked.first()
            used = budget
        }
        // Then the opening, if it still fits: it says whose or what document this is (name, current
        // role, parties, invoice number). The answers come first.
        val opening = labelled(chunks.first())
        if (0 !in chosen && opening.length <= OPENING_CHARS && used + opening.length + SEPARATOR.length <= budget) chosen += 0
        return chosen.sorted().map { chunks[it] } to true
    }

    /** True when the question names a page of the document ("page 3"), which is read directly rather than searched. */
    fun namesPage(question: String): Boolean =
        pageRequest.find(question.lowercase())?.groupValues?.get(1)?.let(::spokenNumber) != null

    /** "What does page 3 say?" in a PDF: that page's text. */
    private fun pageText(text: String, pageStarts: List<Int>, question: String, budget: Int): Pair<List<FilyPassage>, Boolean>? {
        if (pageStarts.isEmpty()) return null
        val page = pageRequest.find(question.lowercase())?.groupValues?.get(1)?.let(::spokenNumber) ?: return null
        if (page !in 1..pageStarts.size) return null
        val start = pageStarts[page - 1].coerceIn(0, text.length)
        val end = pageStarts.getOrNull(page)?.coerceIn(start, text.length) ?: text.length
        return listOf(FilyPassage(text.substring(start, end).trim().take(budget), page)) to true
    }

    private fun start(text: String, pageStarts: List<Int>, budget: Int): Pair<List<FilyPassage>, Boolean> {
        val passages = FilyPassages.pages(text.take(budget), pageStarts)
        val cost = passages.sumOf { labelled(it).length } + (passages.size - 1).coerceAtLeast(0) * SEPARATOR.length
        return passages to (text.length > budget || cost > budget)
    }

    private fun labelled(chunk: FilyPassage): String =
        if (chunk.page != null) "[page ${chunk.page}] ${chunk.text}" else chunk.text

    private fun terms(question: String): Set<String> =
        words(question).filter { it.length >= 3 && it !in stopWords }.map(::stem).toSet()

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty)

    /** Crude suffix folding, so "payments" matches "payment" and "due" matches "due". */
    private fun stem(word: String): String = when {
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("es") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        else -> word
    }

    /** Per PDF page when page starts are known, so no passage spans two pages. */
    private fun chunks(text: String, pageStarts: List<Int>): List<FilyPassage> =
        FilyPassages.pages(text, pageStarts).flatMap { page ->
            chunk(page.text).map { FilyPassage(it, page.page) }
        }

    /** Paragraphs, merged up to about [CHUNK_CHARS] and split at sentences when longer. */
    private fun chunk(text: String): List<String> {
        val pieces = text.split(Regex("\\n\\s*\\n|\\r?\\n")).map(String::trim).filter(String::isNotEmpty)
            .flatMap { paragraph ->
                if (paragraph.length <= CHUNK_CHARS) listOf(paragraph)
                else paragraph.split(Regex("(?<=[.!?])\\s+")).flatMap(::wordChunks)
            }
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + piece.length + 1 > CHUNK_CHARS) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /** A long sentence (PDF text often has no full stops) split between words, never inside one. */
    private fun wordChunks(sentence: String): List<String> {
        if (sentence.length <= CHUNK_CHARS) return listOf(sentence)
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (word in sentence.split(' ')) {
            if (current.isNotEmpty() && current.length + word.length + 1 > CHUNK_CHARS) {
                out += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word.take(CHUNK_CHARS))
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
