package com.xfiles.feature.reader.domain

import com.xfiles.documents.structure.ContentBlock
import javax.inject.Inject

/** A place in the book where the search text was found: its [block] and a short [snippet] around the match. */
data class BookSearchHit(val block: Int, val start: Int, val snippet: String)

/** The words of a block that has any, for quoting and searching; other blocks have none. */
internal fun ContentBlock.plainText(): String = when (this) {
    is ContentBlock.Heading -> text
    is ContentBlock.Paragraph -> text
    is ContentBlock.ListItem -> text
    is ContentBlock.Quote -> text
    is ContentBlock.Code -> text
    else -> ""
}

/** Finds the text in a book's blocks, ignoring case. Runs off the main thread (the caller dispatches it). */
class BookSearchUseCase @Inject constructor() {
    operator fun invoke(blocks: List<ContentBlock>, query: String): List<BookSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<BookSearchHit>()
        for (index in blocks.indices) {
            val text = blocks[index].plainText()
            if (text.isEmpty()) continue
            var at = text.indexOf(needle, ignoreCase = true)
            while (at >= 0) {
                hits += BookSearchHit(index, at, snippetAround(text, at, needle.length))
                if (hits.size >= MAX_HITS) return hits
                at = text.indexOf(needle, at + needle.length, ignoreCase = true)
            }
        }
        return hits
    }

    private fun snippetAround(text: String, at: Int, length: Int): String {
        val from = (at - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (at + length + SNIPPET_CONTEXT).coerceAtMost(text.length)
        val body = text.substring(from, to).replace('\n', ' ').trim()
        return (if (from > 0) "..." else "") + body + (if (to < text.length) "..." else "")
    }

    private companion object {
        const val MAX_HITS = 400
        const val SNIPPET_CONTEXT = 40
    }
}
