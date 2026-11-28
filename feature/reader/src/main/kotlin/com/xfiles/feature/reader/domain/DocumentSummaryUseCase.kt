package com.xfiles.feature.reader.domain

import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.feature.reader.SummaryState
import javax.inject.Inject

/** The "At a glance" summary: of [shown] text when the screen has it, else of the file's extracted text. */
class DocumentSummaryUseCase @Inject constructor(
    private val documents: DocumentRepository,
) {
    suspend operator fun invoke(file: FileEntry, shown: String?, password: String?): SummaryState {
        val text = shown ?: documents.extractText(file, EXTRACT_LIMIT, password)
        return if (text.isBlank()) SummaryState.Error else SummaryState.Ready(documents.summary(text))
    }

    private companion object {
        const val EXTRACT_LIMIT = 32_000
    }
}
