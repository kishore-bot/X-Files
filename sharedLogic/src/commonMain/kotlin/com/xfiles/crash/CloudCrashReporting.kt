package com.xfiles.crash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Opt-in cloud crash reporting (Firebase Crashlytics, firebase build only). Off by default: the user
 * turns it on in About. Only technical crash diagnostics are ever sent; documents, file names and
 * document text never are (see [CrashScrubber]).
 */
interface CloudCrashReporting {
    /** False in builds without the SDK; the About screen hides the switch then. */
    val available: Boolean

    /** The stored user choice. */
    val enabled: StateFlow<Boolean>

    /** Whether the SDK is running in this process. Fixed at process start from the stored consent. */
    val active: Boolean

    /** Saves the choice; throws if it could not be stored. */
    suspend fun setEnabled(enabled: Boolean)
}

/** The offline build: nothing to switch on. */
object NoCloudCrashReporting : CloudCrashReporting {
    override val available = false
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false)
    override val active = false
    override suspend fun setEnabled(enabled: Boolean) {}
}
