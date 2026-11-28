package com.xfiles.crash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Automatic upload of technical crash reports to Firestore. Off by default. */
interface CloudCrashReporting {
    val available: Boolean
    val enabled: StateFlow<Boolean>

    /** Saves the choice; throws if it could not be stored. */
    suspend fun setEnabled(enabled: Boolean)
}

/** Builds without Firestore retain local diagnostics. */
object NoCloudCrashReporting : CloudCrashReporting {
    override val available = false
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun setEnabled(enabled: Boolean) {}
}
