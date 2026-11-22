package com.xfiles.documentengine

import com.xfiles.engine.ffi.FilyModel
import com.xfiles.engine.ffi.ReadException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class FilyGeneration(
    val text: String,
    val tokens: Int,
    val firstTokenMs: Long,
    val elapsedMs: Long,
    val gpu: Boolean,
    /** Prompt tokens decoded for this request (prefill); [reusedTokens] were kept from the KV cache. */
    val prefillTokens: Int = 0,
    val reusedTokens: Int = 0,
    val prefillMs: Long = 0,
)

/**
 * One Fily model at a time. A GPU engine that hangs past its timeout is abandoned and the CPU takes over on
 * a fresh thread, because a native call that is stuck cannot be cancelled and would hold the old thread.
 */
class FilyInference : AutoCloseable {
    @Volatile private var lane: CoroutineDispatcher = newLane()
    @Volatile private var model: FilyModel? = null
    @Volatile private var running = false
    private var configuration: Configuration? = null

    /** True when [path] is the loaded model, on whichever engine it ended up on. */
    fun isLoaded(path: String): Boolean = model != null && configuration?.path == path

    /** Loads [path]. Returns true only when the GPU engine is in use; a GPU that fails or times out gives CPU. */
    suspend fun load(path: String, preferGpu: Boolean, prefix: String): Boolean {
        val request = Configuration(path, preferGpu, prefix)
        if (configuration == request && model != null) return request.preferGpu
        closeCurrent()
        if (!preferGpu) {
            openCpu(request)
            return false
        }
        val opening = CoroutineScope(lane).async { FilyModel(path, true, prefix) }
        val gpu = try {
            withTimeoutOrNull(GPU_LOAD_TIMEOUT_MS) { opening.await() }
        } catch (error: CancellationException) {
            abandon(opening)
            throw error
        } catch (error: Exception) {
            null
        }
        if (gpu != null) {
            model = gpu
            configuration = request
            return true
        }
        abandon(opening)
        openCpu(request)
        return false
    }

    suspend fun complete(prompt: String, grammar: String): FilyGeneration {
        val config = checkNotNull(configuration) { "Fily model is not loaded" }
        val active = checkNotNull(model) { "Fily model is not loaded" }
        if (!config.preferGpu) return withContext(lane) { generate(active, prompt, grammar) }

        // Not a child of this call: a hung native call cannot be cancelled, so it is left behind.
        val call = CoroutineScope(lane).async { generate(active, prompt, grammar) }
        val gpuAnswer = try {
            withTimeoutOrNull(GPU_RESPONSE_TIMEOUT_MS) { call.await() }
        } catch (error: ReadException.LimitExceeded) {
            throw error // The CPU has the same limits; reloading there would only waste time.
        } catch (error: ReadException.Cancelled) {
            throw CancellationException("Fily request cancelled").apply { initCause(error) }
        } catch (error: ReadException) {
            null
        }
        if (gpuAnswer != null) return gpuAnswer

        // The GPU threw or did not answer in time. Its model is freed once its call has really returned.
        call.invokeOnCompletion { runCatching { active.close() } }
        model = null
        lane = newLane()
        val cpu = withContext(lane) { FilyModel(config.path, false, config.prefix) }
        model = cpu
        configuration = config.copy(preferGpu = false)
        return withContext(lane) { generate(cpu, prompt, grammar) }
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

    private suspend fun openCpu(request: Configuration) {
        val cpu = withContext(lane) { FilyModel(request.path, false, request.prefix) }
        model = cpu
        configuration = request.copy(preferGpu = false)
    }

    private suspend fun closeCurrent() {
        val previous = model ?: return
        model = null
        configuration = null
        withContext(lane) { runCatching { previous.close() } }
    }

    /** A GPU load that did not finish in time: close it if it ever finishes, and move off its thread. */
    private fun abandon(opening: Deferred<FilyModel>) {
        opening.invokeOnCompletion { runCatching { opening.getCompleted().close() } }
        lane = newLane()
    }

    private fun generate(engine: FilyModel, prompt: String, grammar: String): FilyGeneration {
        running = true
        try {
            val output = engine.complete(prompt, grammar, MAX_TOKENS.toUInt())
            Log.i(TAG, "prefill ${output.prefillTokens} tokens (${output.prefillMs} ms), reused ${output.reusedTokens}, " +
                "first token ${output.firstTokenMs} ms, total ${output.elapsedMs} ms, ${output.tokens} generated, gpu=${output.gpu}")
            return FilyGeneration(
                text = output.text,
                tokens = output.tokens.toInt(),
                firstTokenMs = output.firstTokenMs.toLong(),
                elapsedMs = output.elapsedMs.toLong(),
                gpu = output.gpu,
                prefillTokens = output.prefillTokens.toInt(),
                reusedTokens = output.reusedTokens.toInt(),
                prefillMs = output.prefillMs.toLong(),
            )
        } catch (error: ReadException.Cancelled) {
            throw CancellationException("Fily request cancelled").apply { initCause(error) }
        } finally {
            running = false
        }
    }

    private data class Configuration(val path: String, val preferGpu: Boolean, val prefix: String)

    private companion object {
        const val TAG = "FilyInference"

        /** Far above a normal load; past this the GPU is treated as hung and the CPU loads instead. */
        const val GPU_LOAD_TIMEOUT_MS = 30_000L

        /** Far above a normal answer (a few seconds) but short enough not to look frozen for long. */
        const val GPU_RESPONSE_TIMEOUT_MS = 30_000L
        const val MAX_TOKENS = 128

        fun newLane(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}
