package com.xfiles.fily

/** A passage of a document for the prompt; [page] is the 1-based PDF page it starts on. */
data class FilyPassage(val text: String, val page: Int?)

/** What retrieval found for a question about one document. */
sealed interface FilyRetrieval {
    /** The whole document fits the budget, so the plain excerpt is the better answer. */
    data object Whole : FilyRetrieval

    /** The passages that answer the question, in document order. [omitted] means more of the document exists. */
    data class Passages(val passages: List<FilyPassage>, val omitted: Boolean) : FilyRetrieval
}

/**
 * Lays retrieved passages out where a document excerpt goes today: "[page N] text" per passage, joined by
 * [Passages.SEPARATOR]. The character budget is counted the same way by the engine's selection.
 */
internal object FilyPassages {
    data class Packed(val text: String, val passages: List<FilyPassage>)

    fun render(passages: List<FilyPassage>, budget: Int): String = pack(passages, budget).text

    /** Keep the model's prompt and the verifier's evidence bounded to the same visible text. */
    fun pack(passages: List<FilyPassage>, budget: Int): Packed {
        val text = StringBuilder()
        val evidence = mutableListOf<FilyPassage>()
        for (passage in passages) {
            val prefix = (if (text.isEmpty()) "" else Passages.SEPARATOR) +
                (passage.page?.let { "[page $it] " } ?: "")
            val remaining = budget - text.length
            if (remaining <= 0) break
            text.append(prefix.take(remaining))
            val visible = passage.text.take((budget - text.length).coerceAtLeast(0))
            text.append(visible)
            if (visible.isNotBlank()) evidence += passage.copy(text = visible)
        }
        return Packed(text.toString(), evidence)
    }

    fun pages(text: String, starts: List<Int>): List<FilyPassage> {
        if (starts.isEmpty()) return listOf(FilyPassage(text, null))
        return starts.indices.mapNotNull { index ->
            val start = starts[index].coerceIn(0, text.length)
            val end = starts.getOrNull(index + 1)?.coerceIn(start, text.length) ?: text.length
            if (start == end) null else FilyPassage(text.substring(start, end), index + 1)
        }
    }
}
