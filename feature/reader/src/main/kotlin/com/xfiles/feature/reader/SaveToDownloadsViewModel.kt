package com.xfiles.feature.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.feature.reader.domain.SaveResult
import com.xfiles.feature.reader.domain.SaveToDownloadsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the reader shows for "Save to Downloads". */
data class SaveUiState(
    val saving: Boolean = false,
    /** 0..1 while copying; null when the size is unknown. */
    val progress: Float? = null,
    /** One-shot result text for the snackbar; the screen clears it with [SaveUiEvent.Consumed]. */
    val message: String? = null,
    /** Set when the copy needs WRITE_EXTERNAL_STORAGE (API 28 and below). */
    val needsPermission: Boolean = false,
)

sealed interface SaveUiEvent {
    data class Save(val uri: Uri, val name: String) : SaveUiEvent

    /** The storage permission was granted; retries the pending save. */
    data object PermissionGranted : SaveUiEvent

    data object PermissionDenied : SaveUiEvent

    data object Consumed : SaveUiEvent
}

@HiltViewModel
class SaveToDownloadsViewModel @Inject constructor(
    private val saveToDownloads: SaveToDownloadsUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(SaveUiState())
    val state: StateFlow<SaveUiState> = _state.asStateFlow()

    private var pending: SaveUiEvent.Save? = null
    private var job: Job? = null

    fun onEvent(event: SaveUiEvent) {
        when (event) {
            is SaveUiEvent.Save -> {
                pending = event
                start(event)
            }
            SaveUiEvent.PermissionGranted -> pending?.let(::start)
            SaveUiEvent.PermissionDenied -> {
                pending = null
                _state.value = SaveUiState(message = "Storage permission is needed to save to Downloads.")
            }
            SaveUiEvent.Consumed -> _state.value = _state.value.copy(message = null, needsPermission = false)
        }
    }

    private fun start(event: SaveUiEvent.Save) {
        if (job?.isActive == true) return
        _state.value = SaveUiState(saving = true, progress = 0f)
        job = viewModelScope.launch {
            val result = saveToDownloads(event.uri, event.name) { fraction ->
                _state.value = _state.value.copy(progress = fraction)
            }
            _state.value = when (result) {
                is SaveResult.Saved -> {
                    pending = null
                    SaveUiState(message = "Saved to Downloads as ${result.name}")
                }
                SaveResult.NeedsStoragePermission -> SaveUiState(needsPermission = true)
                is SaveResult.Failed -> {
                    pending = null
                    SaveUiState(message = result.reason)
                }
            }
        }
    }
}
