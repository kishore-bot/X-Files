package com.xfiles.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.crash.CrashReport
import com.xfiles.feedback.FeedbackResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CrashReportState(
    val report: CrashReport? = null,
    val sending: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
internal class CrashReportViewModel @Inject constructor(
    private val reports: AndroidCrashReports,
    private val cloud: CrashReportingSetup,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CrashReportState())
    val state = mutableState.asStateFlow()
    val canSend = cloud.available

    init {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { reports.pending() } ?: return@launch
            mutableState.value = CrashReportState(report)
            if (cloud.shouldSendAutomatically(report)) send()
        }
    }

    fun send() {
        val current = mutableState.value
        val report = current.report ?: return
        if (!canSend || current.sending) return
        mutableState.value = current.copy(sending = true, error = null)
        viewModelScope.launch {
            when (val result = cloud.send(report)) {
                FeedbackResult.Sent, FeedbackResult.Queued -> clear(report)
                is FeedbackResult.Failed -> mutableState.value = current.copy(error = result.reason)
            }
        }
    }

    fun close() {
        if (!mutableState.value.sending) mutableState.value = CrashReportState()
    }

    fun dismiss() {
        val current = mutableState.value
        val report = current.report ?: return
        if (!current.sending) viewModelScope.launch { clear(report) }
    }

    private suspend fun clear(report: CrashReport) {
        mutableState.value = CrashReportState()
        withContext(Dispatchers.IO) { reports.dismiss(report.id) }
    }
}
