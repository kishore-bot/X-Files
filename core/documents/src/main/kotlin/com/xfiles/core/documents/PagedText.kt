package com.xfiles.core.documents

/** A document's text with the character offset where each page starts; page 1 is first, and [pageStarts] may be empty. */
data class PagedText(val text: String, val pageStarts: List<Int>) {
    fun limited(limit: Int): PagedText = PagedText(text.take(limit), pageStarts)
}

/** The most text read for passage retrieval; a larger document is indexed only up to here. */
const val MAX_PAGED_CHARS = 1_000_000
