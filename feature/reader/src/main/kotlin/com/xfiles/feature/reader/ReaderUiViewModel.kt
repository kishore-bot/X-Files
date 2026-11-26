package com.xfiles.feature.reader

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds the reader screen's panels and modes. The screen sends [ReaderUiEvent]s and reads [state]. */
@HiltViewModel
class ReaderUiViewModel @Inject constructor(internal val progress: com.xfiles.core.documents.FileProgress) : ViewModel() {
    private val mutableState = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = mutableState.asStateFlow()

    internal fun restoreDocument(key: String?): ReaderUiState {
        val base = ReaderUiState(documentKey = key)
        return if (key == null) base else base.copy(
            showSource = progress.read(key, "source-view") == "true",
            pdfTwoUp = progress.read(key, "pdf-two-up") == "true",
            showGrid = progress.read(key, "pdf-grid") == "true",
            pdfZoom = (progress.read(key, "pdf-zoom")?.toIntOrNull() ?: 100).coerceIn(PDF_MIN_ZOOM, PDF_MAX_ZOOM),
        )
    }

    fun onEvent(event: ReaderUiEvent) {
        mutableState.update { current ->
            val next = current.reduce(event)
            val file = next.documentKey ?: return@update next
            if (event is ReaderUiEvent.DocumentChanged && current.documentKey != file) {
                return@update restoreDocument(file)
            }
            when (event) {
                is ReaderUiEvent.SetSource -> progress.write(file, "source-view", next.showSource.toString())
                is ReaderUiEvent.SetPdfTwoUp -> progress.write(file, "pdf-two-up", next.pdfTwoUp.toString())
                is ReaderUiEvent.SetGrid -> progress.write(file, "pdf-grid", next.showGrid.toString())
                is ReaderUiEvent.SetPdfZoom -> progress.write(file, "pdf-zoom", next.pdfZoom.toString())
                else -> Unit
            }
            next
        }
    }
}
