package com.xfiles.documentengine

import com.xfiles.engine.ffi.FilyModel
import com.xfiles.engine.ffi.ReadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class FilyGeneration(
    val text: String,
    val tokens: Int,
    val firstTokenMs: Long,
    val elapsedMs: Long,
    val gpu: Boolean,
)

class FilyInference : AutoCloseable {
    private val lane = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var model: FilyModel? = null
    @Volatile private var running = false
    private var configuration: Configuration? = null

    suspend fun load(path: String, preferGpu: Boolean, prefix: String) {
        val request = Configuration(path, preferGpu, prefix)
        val existing = configuration
        if (existing == request && model != null) return
        var opened: FilyModel? = null
        try {
            withContext(lane) {
                model?.close()
                model = null
                opened = FilyModel(path, preferGpu, prefix)
            }
            model = opened
            configuration = request
        } catch (error: Throwable) {
            opened?.close()
            throw error
        }
    }

    suspend fun complete(prompt: String, grammar: String): FilyGeneration = withContext(lane) {
        val active = checkNotNull(model) { "Fily model is not loaded" }
        val config = checkNotNull(configuration)
        ensureActive()
        running = true
        val output = try {
            active.complete(prompt, grammar, 128u)
        } catch (error: ReadException.Cancelled) {
            throw CancellationException("Fily request cancelled").apply { initCause(error) }
        } catch (error: ReadException.LimitExceeded) {
            throw error // The CPU has the same limits; reloading there would only waste time.
        } catch (error: ReadException) {
            if (!config.preferGpu) throw error
            active.close()
            model = null
            val fallback = FilyModel(config.path, false, config.prefix)
            model = fallback
            configuration = config.copy(preferGpu = false)
            ensureActive()
            fallback.complete(prompt, grammar, 128u)
        } finally {
            running = false
        }
        FilyGeneration(output.text, output.tokens.toInt(), output.firstTokenMs.toLong(), output.elapsedMs.toLong(), output.gpu)
    }

    /** Stops the request in flight. Idle calls are ignored so they cannot cancel the next request. */
    fun cancel() {
        if (running) model?.let { current -> runCatching { current.cancel() } }
    }

    override fun close() {
        val active = model
        model = null
        configuration = null
        if (active != null) {
            runCatching { active.cancel() }
            active.close()
        }
    }

    private data class Configuration(val path: String, val preferGpu: Boolean, val prefix: String)
}
