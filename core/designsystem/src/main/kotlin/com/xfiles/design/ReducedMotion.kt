package com.xfiles.design

import android.animation.ValueAnimator
import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Follows the system's motion preference and feeds [LocalReducedMotion]. Android's "Remove animations" switch
 * sets the animator duration scale to 0, so that scale is the signal. It is read once on [start] and again on
 * every change, off the main thread; the ContentObserver only announces the change.
 */
class ReducedMotionMonitor(private val resolver: ContentResolver) {
    private val mutable = MutableStateFlow(!ValueAnimator.areAnimatorsEnabled())
    val reduced: StateFlow<Boolean> = mutable.asStateFlow()

    private var scope: CoroutineScope? = null
    private var observer: ContentObserver? = null

    fun start() {
        if (observer != null) return
        val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = work
        val listener = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
        }
        observer = listener
        WATCHED.forEach { resolver.registerContentObserver(it, false, listener) }
        refresh()
    }

    fun stop() {
        observer?.let(resolver::unregisterContentObserver)
        observer = null
        scope?.cancel()
        scope = null
    }

    private fun refresh() {
        scope?.launch { mutable.value = isReduced(resolver) }
    }

    companion object {
        private val WATCHED: List<Uri> = listOf(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.WINDOW_ANIMATION_SCALE),
        )

        /** True when the system animator scale is zero (Remove animations, or a developer setting at 0). */
        fun isReduced(resolver: ContentResolver): Boolean =
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f
    }
}
