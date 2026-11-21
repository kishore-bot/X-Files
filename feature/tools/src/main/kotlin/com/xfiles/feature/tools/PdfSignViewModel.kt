package com.xfiles.feature.tools

import android.graphics.Bitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xfiles.core.documents.AndroidPdfDocument
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.PdfToolsRepository
import com.xfiles.documents.MAX_SAVED_SIGNATURES
import com.xfiles.documents.PdfSavedSignature
import com.xfiles.documents.PdfSignPlacement
import com.xfiles.documents.PdfSignSpec
import com.xfiles.documents.PdfSignStroke
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pad and the placement. Pad strokes are fractions of the pad, and the
 * placement is a box in fractions of the page. [pageAspect] is the page's width
 * over its height, so the box keeps the pad's proportions on screen.
 */
data class PdfSignInput(
    val strokes: List<PdfSignStroke> = emptyList(),
    val page: Int = 0,
    val x: Float = 0.6f,
    val y: Float = 0.78f,
    val width: Float = 0.3f,
    val height: Float = 0.1f,
    val pageAspect: Float = 0.7071f,
    val color: Int = 0x1A237E,
    val lineWidth: Float = 1.6f,
)

@HiltViewModel
class PdfSignViewModel @Inject constructor(
    tools: PdfToolsRepository,
    documents: DocumentRepository,
    pdf: AndroidPdfDocument,
    private val store: PdfSignStore,
) : PdfSingleFileToolViewModel<PdfSignInput>(PdfSignInput(), tools, documents, pdf) {
    private val mutableSaved = MutableStateFlow<List<PdfSavedSignature>>(emptyList())
    val saved: StateFlow<List<PdfSavedSignature>> = mutableSaved.asStateFlow()

    val input: StateFlow<PdfSignInput> get() = tool

    init {
        // Preferences are read off the main thread; the pad is usable while this runs.
        viewModelScope.launch(Dispatchers.IO) { mutableSaved.value = store.load() }
    }

    override fun onReleased() {
        setTool(PdfSignInput())
    }

    /** Adds a finished pen stroke to the pad. Empty strokes are ignored. */
    fun addStroke(points: PdfSignStroke) {
        if (points.size < 2) return
        setTool(input.value.copy(strokes = input.value.strokes + listOf(points)))
    }

    fun undoStroke() {
        setTool(input.value.copy(strokes = input.value.strokes.dropLast(1)))
    }

    fun clearPad() {
        setTool(input.value.copy(strokes = emptyList()))
    }

    /** Keeps the pad's strokes as a saved signature, up to [MAX_SAVED_SIGNATURES]. */
    fun saveCurrent() {
        val strokes = input.value.strokes
        if (strokes.isEmpty()) return
        if (mutableSaved.value.size >= MAX_SAVED_SIGNATURES) {
            mutateFrame { it.copy(error = "You can keep up to $MAX_SAVED_SIGNATURES signatures. Remove one to add another.") }
            return
        }
        val next = mutableSaved.value + PdfSavedSignature(strokes)
        mutableSaved.value = next
        store.store(next)
    }

    fun useSaved(index: Int) {
        val signature = mutableSaved.value.getOrNull(index) ?: return
        setTool(input.value.copy(strokes = signature.strokes))
    }

    fun deleteSaved(index: Int) {
        val next = mutableSaved.value.filterIndexed { position, _ -> position != index }
        mutableSaved.value = next
        store.store(next)
    }

    fun setPage(page: Int) {
        if (page < 0 || (frame.value.pageCount > 0 && page >= frame.value.pageCount)) return
        setTool(input.value.copy(page = page))
    }

    /** Records the page's aspect, which sets the box's height for its width. */
    fun setPageAspect(aspect: Float) {
        if (!aspect.isFinite() || aspect <= 0f) return
        val current = input.value
        setTool(current.copy(pageAspect = aspect, height = heightFor(current.width, aspect)))
    }

    /** Moves the box by a fraction of the page, kept on the page. */
    fun moveBy(dx: Float, dy: Float) {
        val current = input.value
        val x = (current.x + dx).coerceIn(0f, 1f - current.width)
        val y = (current.y + dy).coerceIn(0f, 1f - current.height)
        setTool(current.copy(x = x, y = y))
    }

    /** Resizes from the corner, keeping the pad's proportions and the page bounds. */
    fun resizeBy(dWidth: Float) {
        val current = input.value
        // height = width * pageAspect / ASPECT must stay within the page below the box.
        val maxWidth = minOf(1f - current.x, (1f - current.y) * ASPECT / current.pageAspect)
        val width = (current.width + dWidth).coerceIn(MIN_WIDTH, maxOf(MIN_WIDTH, maxWidth))
        setTool(current.copy(width = width, height = heightFor(width, current.pageAspect)))
    }

    fun apply() {
        val current = input.value
        val spec = PdfSignSpec(
            placement = PdfSignPlacement(current.page, current.x, current.y, current.width, current.height),
            strokes = current.strokes,
            color = current.color,
            lineWidth = current.lineWidth,
        )
        spec.validationError()?.let { problem ->
            mutateFrame { it.copy(error = problem) }
            return
        }
        runTool { opened, progress -> listOf(tools.sign(opened, spec, protectionChoice, progress)) }
    }

    suspend fun pagePreview(page: Int): Bitmap = thumbnail(page, PREVIEW)

    private fun heightFor(width: Float, pageAspect: Float): Float = width * pageAspect / ASPECT

    companion object {
        /** Width over height of the signature box, matching the pad. */
        const val ASPECT = 3f
        private const val MIN_WIDTH = 0.08f
        private const val PREVIEW = 900
    }
}
