package com.xfiles.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfMatchBox
import com.xfiles.core.documents.PdfPageText
import com.xfiles.documents.PdfBox
import com.xfiles.documents.PdfMarkup

/**
 * The PDF page area. Reading views show the continuous page column (or two pages a row) with find highlights,
 * text selection and the page style's colour filter; the annotation canvas replaces it while editing. The
 * signature badge sits over the pages when not editing.
 */
@Composable
internal fun ReaderPdfPage(
    ready: ReaderState.Ready,
    preview: FilePreview.Pdf,
    ui: ReaderUiState,
    onEvent: (ReaderUiEvent) -> Unit,
    rotation: Int,
    markup: PdfMarkupState,
    pdfFind: PdfFindState,
    pdfText: suspend (Int) -> PdfPageText?,
    loadPage: suspend (Int) -> FilePreview.Pdf?,
    onAddMarkup: (PdfMarkup) -> Unit,
    onPage: (Int) -> Unit,
    onPdfQuery: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (ui.editingMarkup) {
            PdfMarkupCanvas(
                preview.bitmap,
                preview.page,
                ui.markupMode,
                markup.marks.filter { it.page == preview.page },
                enabled = !markup.working && !ready.turningPage,
                onAdd = onAddMarkup,
            )
        } else {
            val active = pdfFind.matches.getOrNull(pdfFind.selected)
            fun highlights(page: Int): List<PdfMatchBox> = active?.takeIf { ui.showPdfFind && it.page == page }?.boxes.orEmpty()
            // Highlights made in this session show on their page; the marks save with the annotated copy.
            fun marksOn(page: Int): List<PdfBox> = markup.marks.asSequence()
                .filterIsInstance<PdfMarkup.Highlight>()
                .filter { it.page == page }
                .flatMap { it.boxes.asSequence() }
                .toList()
            fun textSource(page: Int): PdfTextSource = PdfTextSource(
                page = page,
                load = { pdfText(page) },
                onHighlight = { boxes -> onAddMarkup(PdfMarkup.Highlight(page, boxes)) },
                onSearch = { query ->
                    onEvent(ReaderUiEvent.SetPdfFind(true))
                    onPdfQuery(query)
                },
            )
            PdfContinuousView(
                pageCount = preview.pageCount,
                currentPage = preview.page,
                twoUp = ui.pdfTwoUp,
                zoom = ui.pdfZoom,
                loadPage = loadPage,
                highlights = ::highlights,
                marks = ::marksOn,
                textSource = ::textSource,
                colorFilter = LocalPageStyle.current.pageFilter(),
                onPage = onPage,
                onZoom = { onEvent(ReaderUiEvent.SetPdfZoom(it)) },
            )
        }
        if (!ui.editingMarkup) {
            PdfSignatureBadge(ready.file.uri, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
    }
}
