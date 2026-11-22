package com.xfiles.android

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide startup state shared by the system splash, its exit animation and the first Compose frame.
 *
 * One continuous sequence on a cold start:
 *  1. The system splash with the animated mark. It stays up until the first library listing is ready
 *     ([markDataReady]), at least [MIN_SPLASH_MS] has passed, and never past [MAX_SPLASH_MS].
 *  2. The exit animation ([beginExit]): the mark scales and fades while "Xfiles" and the tagline reveal over the
 *     app's first frame. The exit lasts what is left of [TOTAL_MS] (clamped), so a cold start takes about 1.8 s.
 *  3. A tap during the sequence ([skipIntro]) ends it at once.
 *
 * [claimIntro] is true once per process: warm starts and configuration changes skip the exit.
 */
object StartupGate {
    private const val MIN_SPLASH_MS = 300L
    private const val MAX_SPLASH_MS = 1000L
    private const val TOTAL_MS = 1800L
    private const val MIN_EXIT_MS = 600L
    private const val MAX_EXIT_MS = 1200L

    private val introClaimed = AtomicBoolean(false)
    private val dataReady = AtomicBoolean(false)
    private val startedAt = SystemClock.uptimeMillis()
    private val mutableExitMs = MutableStateFlow(0)
    private val mutableSkipped = MutableStateFlow(false)

    /** The exit's length in ms once it has begun; 0 before that. */
    val exitMs: StateFlow<Int> = mutableExitMs.asStateFlow()

    /** True when the user tapped to skip the sequence. */
    val skipped: StateFlow<Boolean> = mutableSkipped.asStateFlow()

    fun claimIntro(): Boolean = introClaimed.compareAndSet(false, true)

    fun markDataReady() {
        dataReady.set(true)
    }

    /** True while the system splash should keep showing. Evaluated every frame, so it only reads flags. */
    fun keepSplash(): Boolean {
        val elapsed = SystemClock.uptimeMillis() - startedAt
        // Never hold the splash past MAX_SPLASH_MS, even if no listing arrives (empty or failed load).
        return elapsed < MAX_SPLASH_MS && !(dataReady.get() && elapsed >= MIN_SPLASH_MS)
    }

    /**
     * Starts the exit and returns its length: what remains of [TOTAL_MS] since process start, clamped so the
     * exit is never a flicker and never drags. The splash view animates for the same length.
     */
    fun beginExit(): Int {
        val elapsed = SystemClock.uptimeMillis() - startedAt
        val length = (TOTAL_MS - elapsed).coerceIn(MIN_EXIT_MS, MAX_EXIT_MS).toInt()
        mutableExitMs.value = length
        return length
    }

    fun skipIntro() {
        mutableSkipped.value = true
    }
}
