package com.xfiles.feature.reader.domain

import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.PdfPageText
import javax.inject.Inject
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A bookmark's place: the page's first line as its label, and the words after it as the snippet. The place is
 * best effort: it waits at most briefly for the page's text, and a bookmark saves without one.
 */
class BookmarkPlaceUseCase @Inject constructor(
    private val pages: PdfPagesUseCase,
) {
    /** (label, snippet) for PDF page [pageIndex], or null when the text is not read in time. */
    suspend operator fun invoke(file: FileEntry, pageIndex: Int, password: String?): Pair<String?, String?>? =
        withTimeoutOrNull(TIMEOUT_MS) { pages.text(file, pageIndex, password) }?.let(::placeOf)

    private fun placeOf(text: PdfPageText): Pair<String?, String?> {
        val lines = text.text.lineSequence().map { WHITESPACE.replace(it, " ").trim() }.filter { it.isNotEmpty() }.toList()
        val label = lines.firstOrNull()?.take(LABEL_CHARS)?.ifBlank { null }
        val snippet = lines.drop(1).joinToString(" ").ifBlank { lines.firstOrNull().orEmpty() }
            .take(SNIPPET_CHARS).ifBlank { null }
        return label to snippet
    }

    private companion object {
        const val TIMEOUT_MS = 1_500L
        const val LABEL_CHARS = 40
        const val SNIPPET_CHARS = 80
        val WHITESPACE = Regex("\\s+")
    }
}
