package com.xfiles.feature.tidy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.xfiles.core.documents.DocumentSharing
import com.xfiles.core.documents.FolderRepository
import com.xfiles.tidy.TidyFile
import com.xfiles.tidy.TidyProgress
import com.xfiles.tidy.TidyReport
import com.xfiles.tidy.TidyService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TidyUiState(
    val report: TidyReport? = null,
    val progress: TidyProgress? = null,
    val failure: String? = null,
    val notice: String? = null,
    val hasFolders: Boolean = true,
    val canDelete: Boolean = false,
)

/**
 * Screen state for Tidy up. The scan itself lives in the singleton [TidyService], so it keeps running
 * if the screen is left and its result is there when the screen comes back.
 */
@HiltViewModel
class TidyViewModel @Inject constructor(
    private val service: TidyService,
    private val folders: FolderRepository,
    private val sharing: DocumentSharing,
) : ViewModel() {
    fun share(file: TidyFile) {
        if (!sharing.share(listOf(Uri.parse(file.uri) to file.name))) {
            local.update { it.copy(notice = "No app on this phone can take this file.") }
        }
    }

    private val local = MutableStateFlow(TidyUiState(canDelete = service.canDelete))

    val state: StateFlow<TidyUiState> = combine(service.report, service.progress, service.failure, local) { report, progress, failure, ui ->
        ui.copy(report = report, progress = progress, failure = failure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            service.load()
            local.update { it.copy(hasFolders = folders.hasFolders()) }
        }
    }

    fun scan() = service.scan()

    fun cancel() = service.cancel()

    fun delete(file: TidyFile) {
        viewModelScope.launch {
            val deleted = service.delete(file)
            local.update {
                it.copy(
                    notice = if (deleted) "Deleted ${file.name}." else
                        "Nothing was deleted. The copies may have changed, a scan may be running, or write access is missing. " +
                            "Remove the folder in Library and add it again to grant access, then scan again.",
                )
            }
        }
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }
}
