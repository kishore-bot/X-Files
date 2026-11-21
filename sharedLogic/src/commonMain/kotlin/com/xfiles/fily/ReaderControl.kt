package com.xfiles.fily

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the open viewer is, one-based, as the user would say it. */
data class ReaderPosition(
    val page: Int? = null,
    val pageCount: Int? = null,
    val sheet: String? = null,
    val cell: String? = null,
)

/** The viewer on screen, as the assistant may drive it: only what a user could do with a tap. */
interface ReaderTarget {
    /** controlReader operations this viewer supports, e.g. "next_page". */
    val operations: Set<String>
    val position: ReaderPosition

    /** Returns null when done, or a short failure reason such as "out_of_range". */
    suspend fun perform(operation: String, value: String?): String?
}

/**
 * Connects the assistant to whichever viewer is showing. Viewers attach while on screen; the
 * library has none, so screen commands there are refused.
 */
class ReaderControl {
    private val current = MutableStateFlow<ReaderTarget?>(null)
    val target: StateFlow<ReaderTarget?> = current.asStateFlow()

    /** Returns the detach function for when the viewer leaves the screen. */
    fun attach(target: ReaderTarget): () -> Unit {
        current.value = target
        return { current.compareAndSet(target, null) }
    }

    /** In the catalog's order, so APP_STATE is stable from one request to the next. */
    val operations: List<String>
        get() = current.value?.operations?.let { ops -> OPERATIONS.filter { it in ops } }.orEmpty()

    /** [failure] is null when the viewer did it; [position] is where it is afterwards. */
    data class Result(val failure: String?, val position: ReaderPosition)

    suspend fun run(operation: String, value: String?): Result {
        val target = current.value ?: return Result("no_document", ReaderPosition())
        if (operation !in target.operations) return Result("unsupported_here", target.position)
        val failure = target.perform(operation, value)
        return Result(failure, target.position)
    }

    /** The controlReader tool result, shaped like the training data. */
    suspend fun perform(operation: String, value: String?): String {
        val result = run(operation, value)
        return buildJsonObject {
            if (result.failure != null) {
                put("status", "failed")
                put("reason", result.failure)
                return@buildJsonObject
            }
            put("status", "done")
            put("operation", operation)
            result.position.page?.let { put("page", it) }
            result.position.pageCount?.let { put("pageCount", it) }
            result.position.sheet?.let { put("sheet", it) }
            result.position.cell?.let { put("cell", it) }
        }.toString()
    }

    companion object {
        /** The trained operations first (training/catalog.py), then the ones added for screen control. */
        val OPERATIONS = listOf(
            "next_page", "previous_page", "go_to_page", "zoom_in", "zoom_out", "show_contents", "show_source",
            "show_preview", "rotate_photo", "next_photo", "previous_photo", "play", "pause", "seek", "subtitles",
            "audio_track", "scroll_down", "scroll_up", "scroll_top", "scroll_bottom", "select_cell", "next_sheet",
            "previous_sheet",
        )
    }
}
