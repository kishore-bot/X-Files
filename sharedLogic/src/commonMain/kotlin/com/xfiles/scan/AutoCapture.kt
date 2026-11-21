package com.xfiles.scan

import kotlin.math.abs
import kotlin.math.max

/** What auto-capture sees in the viewfinder. */
enum class AutoCaptureState {
    /** No page outline in view. */
    Searching,

    /** A page, but moving or blurred. */
    Moving,

    /** A page holding still; the picture is taken soon. */
    Holding,

    /** Held long enough: take the picture now (reported once). */
    Take,

    /** Taken; waits for the page to leave the view before taking another. */
    Taken,
}

/**
 * Takes the picture once the page outline has held still, and sharp, for
 * [holdMs]. Fed from the camera's analysis thread, a few frames a second;
 * not thread-safe, so keep it on that one thread.
 */
class SteadyPage(private val holdMs: Long = HOLD_MS, private val tolerance: Float = TOLERANCE) {
    private var last: PageQuad? = null
    private var since = -1L
    private var taken = false

    /** [quad]: the outline in this frame, as fractions of it; [sharp]: the frame isn't blurred. */
    fun offer(quad: PageQuad?, sharp: Boolean, now: Long): AutoCaptureState {
        val previous = last
        last = quad
        if (quad == null) {
            since = -1
            taken = false
            return AutoCaptureState.Searching
        }
        if (taken) return AutoCaptureState.Taken
        if (previous == null || !sharp || drift(previous, quad) > tolerance) {
            since = -1
            return AutoCaptureState.Moving
        }
        if (since < 0) since = now
        if (now - since < holdMs) return AutoCaptureState.Holding
        taken = true
        return AutoCaptureState.Take
    }

    private companion object {
        /** About half a second, as planned: long enough to be deliberate, short enough not to wait. */
        const val HOLD_MS = 500L

        /** Corners may wander this fraction of the frame between frames: a hand is never perfectly still. */
        const val TOLERANCE = 0.02f
    }
}

/** The furthest any corner moved, as a fraction of the frame. */
internal fun drift(a: PageQuad, b: PageQuad): Float =
    a.corners.zip(b.corners).maxOf { (p, q) -> max(abs(p.x - q.x), abs(p.y - q.y)) }
