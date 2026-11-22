package com.xfiles.fily

/**
 * What the GPU crash guard remembers. A GPU load writes [Trying] before the native call; if the process
 * dies inside it, the next launch finds [Trying] and records [Bad]. A hang or a thrown error also ends in [Bad].
 */
sealed class FilyGpuState(val wire: String) {
    /** Not tried on this build and model yet. */
    data object Untested : FilyGpuState("untested")

    /** A GPU load has started and has not reported back. Found at launch, it means the GPU ended the process. */
    data object Trying : FilyGpuState("trying")

    /** The GPU has answered; later loads skip the crash window. */
    data object Proven : FilyGpuState("proven")

    /** The GPU crashed, hung or failed; the CPU is used until the build or the model file changes. */
    data object Bad : FilyGpuState("bad")

    companion object {
        // A getter, not a stored list: the companion can initialize before the nested objects
        // (class-init order), which would leave nulls in an eagerly built list.
        val all: List<FilyGpuState> get() = listOf(Untested, Trying, Proven, Bad)
        fun fromWire(wire: String): FilyGpuState? = when (wire) {
            Untested.wire -> Untested
            Trying.wire -> Trying
            Proven.wire -> Proven
            Bad.wire -> Bad
            else -> null
        }
    }
}

/** Pure transitions of the GPU guard. No IO here, so every rule is unit-tested directly. */
object FilyGpuPolicy {
    /** [useGpu] for the load; [write] is the state to persist before loading, or null to leave storage alone. */
    data class LoadStart(val useGpu: Boolean, val write: FilyGpuState?)

    fun startLoad(stored: FilyGpuState, preferGpu: Boolean): LoadStart {
        // A leftover "trying" means the last launch died mid-load, so the GPU is ruled out for this build.
        val recovered = if (stored == FilyGpuState.Trying) FilyGpuState.Bad else stored
        val useGpu = preferGpu && recovered != FilyGpuState.Bad
        val next = when {
            !useGpu -> recovered
            recovered == FilyGpuState.Proven -> FilyGpuState.Proven
            else -> FilyGpuState.Trying
        }
        return LoadStart(useGpu, next.takeIf { it != stored })
    }

    /** The state to persist after a GPU-requested answer, or null when nothing changes. */
    fun afterAnswer(stored: FilyGpuState, answeredOnGpu: Boolean): FilyGpuState? = when {
        !answeredOnGpu -> FilyGpuState.Bad.takeIf { stored != it }
        stored == FilyGpuState.Proven -> null
        else -> FilyGpuState.Proven
    }
}

/**
 * Persists [FilyGpuState] for one build stamp (app version and model file). A stored state from another
 * stamp reads as [FilyGpuState.Untested], so an update or a changed model gets one fresh GPU attempt.
 * Blocking storage calls: run from an IO dispatcher.
 */
class FilyGpuGuard(private val preferences: FilyPreferences, private val stamp: String) {
    /** Decides whether this load uses the GPU and commits any state change before the native load starts. */
    fun startLoad(preferGpu: Boolean): Boolean {
        val stored = read()
        val start = FilyGpuPolicy.startLoad(stored, preferGpu)
        start.write?.let(::write)
        return start.useGpu
    }

    /** Records the outcome of a GPU-requested answer: [answeredOnGpu] false means the GPU fell back to CPU. */
    fun answered(answeredOnGpu: Boolean) {
        FilyGpuPolicy.afterAnswer(read(), answeredOnGpu)?.let(::write)
    }

    /** The GPU load itself failed or timed out. */
    fun failed() = write(FilyGpuState.Bad)

    fun read(): FilyGpuState {
        val raw = preferences.get(KEY) ?: return FilyGpuState.Untested
        val separator = raw.lastIndexOf(':')
        if (separator < 0 || raw.substring(0, separator) != stamp) return FilyGpuState.Untested
        return FilyGpuState.fromWire(raw.substring(separator + 1)) ?: FilyGpuState.Untested
    }

    private fun write(state: FilyGpuState) = preferences.putDurably(KEY, "$stamp:${state.wire}")

    companion object {
        const val KEY = "fily.gpu"
    }
}
