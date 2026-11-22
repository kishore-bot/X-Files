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
class ReaderUiViewModel @Inject constructor() : ViewModel() {
    private val mutableState = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = mutableState.asStateFlow()

    fun onEvent(event: ReaderUiEvent) {
        mutableState.update { it.reduce(event) }
    }
}
