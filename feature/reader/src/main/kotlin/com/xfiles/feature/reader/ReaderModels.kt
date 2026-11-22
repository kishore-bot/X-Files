package com.xfiles.feature.reader

import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfMatchBox
import com.xfiles.core.documents.PreparedPdf
import com.xfiles.documents.DocumentAnalysis
import com.xfiles.documents.PdfMarkup

/** What the reader shows for the open file. Immutable: each change is a copy. */
sealed interface ReaderState {
    data object Loading : ReaderState
    data object ReadError : ReaderState
    data object EngineUnavailable : ReaderState
    data class Password(val file: FileEntry, val initialPage: Int, val incorrect: Boolean) : ReaderState
    data class Failure(val message: String) : ReaderState

    data class Ready(
        val file: FileEntry,
        val preview: FilePreview,
        val turningPage: Boolean = false,
        val pageError: Boolean = false,
        val bookmarkError: Boolean = false,
        val summary: SummaryState? = null,
        /** Two-page view: the spread's other page (null for single view, or when there is none). */
        val twoPage: Boolean = false,
        val facing: FilePreview.Pdf? = null,
    ) : ReaderState
}

sealed interface SummaryState {
    data object Loading : SummaryState
    data object Error : SummaryState
    data class Ready(val analysis: DocumentAnalysis) : SummaryState
}

data class PdfFindMatch(val page: Int, val boxes: List<PdfMatchBox>)

data class PdfFindState(
    val matches: List<PdfFindMatch> = emptyList(),
    val selected: Int = 0,
    val loading: Boolean = false,
    val truncated: Boolean = false,
    val error: Boolean = false,
)

data class PdfMarkupState(
    val marks: List<PdfMarkup> = emptyList(),
    val output: PreparedPdf? = null,
    val working: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)
