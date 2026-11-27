package com.xfiles.feature.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.feedback.FeedbackCategory
import com.xfiles.feedback.FeedbackEnvironment
import com.xfiles.feedback.FeedbackReport
import com.xfiles.feedback.FeedbackResult
import com.xfiles.feedback.FeedbackRules
import com.xfiles.feedback.FeedbackSender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Remembers when feedback was last sent, on this phone, so accidental repeat taps cannot spam the team.
 * Only the timestamp is stored.
 */
class FeedbackCooldown @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("feedback", Context.MODE_PRIVATE)

    /** Milliseconds until another send is allowed. A timestamp in the future (clock changed) never waits longer than the window. */
    fun remainingMillis(now: Long = System.currentTimeMillis()): Long {
        val last = preferences.getLong(KEY_LAST_SENT, 0L)
        return (last + WINDOW_MILLIS - now).coerceIn(0L, WINDOW_MILLIS)
    }

    fun markSent(now: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(KEY_LAST_SENT, now).apply()
    }

    companion object {
        const val WINDOW_MILLIS = 30_000L
        private const val KEY_LAST_SENT = "last_sent_at"
    }
}

/** Where the send is, as the sheet shows it. */
sealed interface FeedbackStatus {
    data object Idle : FeedbackStatus
    data object Sending : FeedbackStatus
    data object Sent : FeedbackStatus
    data object Queued : FeedbackStatus
    data class Failed(val reason: String) : FeedbackStatus
}

data class FeedbackUiState(
    val available: Boolean = true,
    val category: FeedbackCategory = FeedbackCategory.Bug,
    val message: String = "",
    val includeDetails: Boolean = true,
    val status: FeedbackStatus = FeedbackStatus.Idle,
    val cooldownSeconds: Int = 0,
) {
    val messageLength: Int get() = FeedbackRules.length(message)
    val messageTooLong: Boolean get() = messageLength > FeedbackRules.MAX_MESSAGE_LENGTH
    val canSend: Boolean
        get() = available && message.isNotBlank() && !messageTooLong &&
            cooldownSeconds == 0 && status != FeedbackStatus.Sending
}

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val sender: FeedbackSender,
    /** Shown in the sheet as "what will be attached". Never sent unless [FeedbackUiState.includeDetails] is on. */
    val environment: FeedbackEnvironment,
    private val cooldown: FeedbackCooldown,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedbackUiState(available = sender.available))
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()
    private var ticker: Job? = null

    init {
        startCooldownTicker()
    }

    fun onCategory(category: FeedbackCategory) = _state.update { it.copy(category = category) }

    fun onMessage(text: String) = _state.update { it.copy(message = text) }

    fun onIncludeDetails(include: Boolean) = _state.update { it.copy(includeDetails = include) }

    fun send() {
        val current = _state.value
        if (!current.canSend) return
        val report = FeedbackReport(
            category = current.category,
            message = current.message.trim(),
            environment = if (current.includeDetails) environment else null,
        )
        _state.update { it.copy(status = FeedbackStatus.Sending) }
        viewModelScope.launch {
            val result = sender.send(report)
            when (result) {
                FeedbackResult.Sent -> finish(FeedbackStatus.Sent)
                FeedbackResult.Queued -> finish(FeedbackStatus.Queued)
                is FeedbackResult.Failed -> _state.update { it.copy(status = FeedbackStatus.Failed(result.reason)) }
            }
        }
    }

    private fun finish(status: FeedbackStatus) {
        cooldown.markSent()
        _state.update {
            it.copy(
                status = status,
                message = "",
            )
        }
        startCooldownTicker()
    }

    /** Counts the cooldown down once a second while it runs; stops at zero. */
    private fun startCooldownTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var seconds = cooldownSeconds()
            _state.update { it.copy(cooldownSeconds = seconds) }
            while (seconds > 0) {
                delay(1_000L)
                seconds = cooldownSeconds()
                _state.update { it.copy(cooldownSeconds = seconds) }
            }
        }
    }

    private fun cooldownSeconds(): Int = ((cooldown.remainingMillis() + 999L) / 1_000L).toInt()
}
