package com.xfiles.core.documents

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Constant trace names only. URIs, filenames and search terms stay out of traces. */
object PerformanceTrace {
    private val cookies = AtomicInteger()

    fun begin(name: String): (() -> Unit)? {
        if (!Trace.isEnabled()) return null
        val cookie = cookies.incrementAndGet()
        Trace.beginAsyncSection(name, cookie)
        return { Trace.endAsyncSection(name, cookie) }
    }

    fun <T> section(name: String, block: () -> T): T {
        if (!Trace.isEnabled()) return block()
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    fun counter(name: String, value: Long) {
        if (Trace.isEnabled()) Trace.setCounter(name, value)
    }

    suspend fun <T> span(name: String, block: suspend () -> T): T {
        val end = begin(name)
        try {
            return block()
        } finally {
            end?.invoke()
        }
    }

    suspend fun <T> onLane(
        name: String,
        queueName: String,
        dispatcher: CoroutineDispatcher,
        block: suspend () -> T,
    ): T {
        var endQueue = begin(queueName)
        try {
            return withContext(dispatcher) {
                endQueue?.invoke()
                endQueue = null
                span(name, block)
            }
        } finally {
            endQueue?.invoke()
        }
    }
}
