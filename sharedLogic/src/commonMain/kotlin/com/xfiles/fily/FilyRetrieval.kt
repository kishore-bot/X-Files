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
    fun render(passages: List<FilyPassage>, budget: Int): String =
        passages.joinToString(Passages.SEPARATOR) { passage ->
            passage.page?.let { "[page $it] ${passage.text}" } ?: passage.text
        }.take(budget)
}
