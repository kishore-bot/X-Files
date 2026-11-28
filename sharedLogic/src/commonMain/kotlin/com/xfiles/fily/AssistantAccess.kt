package com.xfiles.fily

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AssistantAccessDenied : IllegalStateException("Assistant access is off or this request has expired.")

/** Shared by platform assistants and the Info switches. Settings are changed on the UI thread. */
class AssistantAccess(private val storage: FilyPreferences) {
    private data class Policy(val enabled: Boolean = false, val content: Boolean = false, val generation: Long = 0)

    private val policy = MutableStateFlow(load())
    private val enabledState = MutableStateFlow(policy.value.enabled)
    private val contentState = MutableStateFlow(policy.value.content)

    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()
    val contentAccess: StateFlow<Boolean> = contentState.asStateFlow()
    val generation: Long get() = policy.value.generation

    fun setEnabled(on: Boolean) {
        val current = policy.value
        if (on == current.enabled) return
        save(current.copy(enabled = on, content = false, generation = nextGeneration(current.generation)))
    }

    fun setContentAccess(on: Boolean) {
        val current = policy.value
        if (on && !current.enabled) throw AssistantAccessDenied()
        if (on == current.content) return
        save(current.copy(content = on, generation = nextGeneration(current.generation)))
    }

    fun captureAuthorization(): Long {
        val current = policy.value
        if (!current.enabled) throw AssistantAccessDenied()
        return current.generation
    }

    fun requireEnabled() {
        captureAuthorization()
    }

    fun recheckAuthorization(token: Long) {
        val current = policy.value
        if (!current.enabled || current.generation != token) throw AssistantAccessDenied()
    }

    /** Revocation cancels extraction and provider queries as well as withholding their results. */
    internal suspend fun <T> authorized(content: Boolean = false, block: suspend (Long) -> T): T {
        val token = captureAuthorization()
        if (content && !policy.value.content) throw AssistantAccessDenied()
        return coroutineScope {
            val guard = launch(start = CoroutineStart.UNDISPATCHED) {
                policy.first { !it.enabled || it.generation != token }
                throw AssistantAccessDenied()
            }
            try {
                currentCoroutineContext().ensureActive()
                block(token).also { recheckAuthorization(token) }
            } finally {
                guard.cancel()
            }
        }
    }

    private fun save(value: Policy) {
        // Publish the authoritative snapshot first, so an off switch closes access immediately.
        policy.value = value
        enabledState.value = value.enabled
        contentState.value = value.content
        storage.put(KEY, "${value.enabled}|${value.content}|${value.generation}")
    }

    private fun load(): Policy {
        val parts = storage.get(KEY)?.split('|') ?: return Policy()
        if (parts.size != 3) return Policy()
        val enabled = parts[0].toBooleanStrictOrNull() ?: return Policy()
        val content = parts[1].toBooleanStrictOrNull() ?: return Policy()
        val generation = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return Policy()
        return Policy(enabled, content && enabled, generation)
    }

    private fun nextGeneration(value: Long): Long = if (value == Long.MAX_VALUE) 1 else value + 1

    private companion object {
        const val KEY = "assistant_access_v1"
    }
}
