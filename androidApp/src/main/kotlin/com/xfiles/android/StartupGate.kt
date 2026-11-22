package com.xfiles.android

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide startup state shared by the system splash and the Compose intro.
 *
 * - [claimIntro] is true once per process, so the branded intro plays on a cold start only; a warm start
 *   (activity recreated in a live process) and rotation skip it.
 * - The system splash stays up until the first library listing is ready ([markDataReady]) and at least
 *   [MIN_SPLASH_MS] has passed, so the first frame is never a blank window.
 */
object StartupGate {
    private const val MIN_SPLASH_MS = 300L
    private const val MAX_SPLASH_MS = 1500L
    private val introClaimed = AtomicBoolean(false)
    private val dataReady = AtomicBoolean(false)
    private val startedAt = SystemClock.uptimeMillis()

    fun claimIntro(): Boolean = introClaimed.compareAndSet(false, true)

    fun markDataReady() {
        dataReady.set(true)
    }

    /** True while the system splash should keep showing. */
    fun keepSplash(): Boolean {
        val elapsed = SystemClock.uptimeMillis() - startedAt
        // Never hold the splash past MAX_SPLASH_MS, even if no listing arrives (empty or failed load).
        return elapsed < MAX_SPLASH_MS && !(dataReady.get() && elapsed >= MIN_SPLASH_MS)
    }
}
