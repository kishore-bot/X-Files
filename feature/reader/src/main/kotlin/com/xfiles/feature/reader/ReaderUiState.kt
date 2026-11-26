package com.xfiles.feature.reader

import com.xfiles.documents.PdfMarkupKind

/** The PDF zoom range in percent, as the EzFiles reader offers it. */
const val PDF_MIN_ZOOM = 50
const val PDF_MAX_ZOOM = 300
const val PDF_ZOOM_STEP = 25

/**
 * The reader screen's own UI state: panels, modes and the action button for the open document. Immutable;
 * every change is a copy made by [ReaderUiViewModel].
 */
data class ReaderUiState(
    /** The document these flags belong to; a different document starts with everything closed. */
    val documentKey: String? = null,
    val showSearch: Boolean = false,
    val showSource: Boolean = false,
    val showPdfFind: Boolean = false,
    val editingMarkup: Boolean = false,
    val markupMode: PdfMarkupKind = PdfMarkupKind.Pen,
    val confirmDiscard: Boolean = false,
    val showJump: Boolean = false,
    val showOffice: Boolean = false,
    val showGrid: Boolean = false,
    val showPageStyle: Boolean = false,
    /** The toolbar settings popup is expanded. */
    val actionsOpen: Boolean = false,
    /** PDF continuous view: two pages side by side (false = one column). */
    val pdfTwoUp: Boolean = false,
    /** PDF zoom in percent, from [PDF_MIN_ZOOM] to [PDF_MAX_ZOOM]; 100 fits a page to the width. */
    val pdfZoom: Int = 100,
    val visiblePdfPage: Int? = null,
)

/** Everything the reader screen can ask its state holder to change. */
sealed interface ReaderUiEvent {
    /** The screen now shows [key]; flags from an earlier document are cleared. */
    data class DocumentChanged(val key: String?) : ReaderUiEvent
    data class SetSearch(val shown: Boolean) : ReaderUiEvent
    /** Shows or hides the source text; showing it closes the search panel, as the source toggle always has. */
    data class SetSource(val shown: Boolean) : ReaderUiEvent
    data class SetPdfFind(val shown: Boolean) : ReaderUiEvent
    /** Entering annotation mode also closes the PDF find bar and the page grid. */
    data class SetEditingMarkup(val editing: Boolean) : ReaderUiEvent
    data class SetMarkupMode(val mode: PdfMarkupKind) : ReaderUiEvent
    data class SetConfirmDiscard(val shown: Boolean) : ReaderUiEvent
    data class SetJump(val shown: Boolean) : ReaderUiEvent
    data class SetOffice(val shown: Boolean) : ReaderUiEvent
    data class SetGrid(val shown: Boolean) : ReaderUiEvent
    data class SetPageStyleSheet(val shown: Boolean) : ReaderUiEvent
    data class SetActionsOpen(val open: Boolean) : ReaderUiEvent
    data class SetPdfTwoUp(val shown: Boolean) : ReaderUiEvent
    data class SetPdfZoom(val percent: Int) : ReaderUiEvent
    data class SetVisiblePdfPage(val page: Int?) : ReaderUiEvent
}

/** Applies [event] to this state. Pure: the same inputs always give the same copy. */
internal fun ReaderUiState.reduce(event: ReaderUiEvent): ReaderUiState = when (event) {
    is ReaderUiEvent.DocumentChanged -> if (event.key == documentKey) this else ReaderUiState(documentKey = event.key)
    is ReaderUiEvent.SetSearch -> copy(showSearch = event.shown)
    is ReaderUiEvent.SetSource -> copy(showSearch = false, showSource = event.shown)
    is ReaderUiEvent.SetPdfFind -> copy(showPdfFind = event.shown)
    is ReaderUiEvent.SetEditingMarkup ->
        if (event.editing) copy(editingMarkup = true, showPdfFind = false, showGrid = false, actionsOpen = false) else copy(editingMarkup = false)
    is ReaderUiEvent.SetMarkupMode -> copy(markupMode = event.mode)
    is ReaderUiEvent.SetConfirmDiscard -> copy(confirmDiscard = event.shown)
    is ReaderUiEvent.SetJump -> copy(showJump = event.shown)
    is ReaderUiEvent.SetOffice -> copy(showOffice = event.shown)
    is ReaderUiEvent.SetGrid -> copy(showGrid = event.shown)
    is ReaderUiEvent.SetPageStyleSheet -> copy(showPageStyle = event.shown, actionsOpen = false)
    is ReaderUiEvent.SetActionsOpen -> copy(actionsOpen = event.open)
    is ReaderUiEvent.SetPdfTwoUp -> copy(pdfTwoUp = event.shown)
    is ReaderUiEvent.SetVisiblePdfPage -> copy(visiblePdfPage = event.page)
    is ReaderUiEvent.SetPdfZoom -> copy(pdfZoom = event.percent.coerceIn(PDF_MIN_ZOOM, PDF_MAX_ZOOM))
}
