package com.xfiles.feature.reader.domain

import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.feature.reader.PdfFindMatch
import com.xfiles.feature.reader.PdfFindState
import javax.inject.Inject

/** Finds the text in a PDF and lists its matches page by page. Runs off the main thread (suspends on IO). */
class PdfSearchUseCase @Inject constructor(
    private val documents: DocumentRepository,
) {
    suspend operator fun invoke(file: FileEntry, query: String, password: String?): PdfFindState {
        val result = documents.searchPdf(file, query, password)
        val matches = result.pages.flatMap { page ->
            page.matches.map { boxes -> PdfFindMatch(page.page, boxes) }
        }
        return PdfFindState(matches = matches, truncated = result.truncated)
    }
}
