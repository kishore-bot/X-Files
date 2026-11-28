package com.xfiles.feature.tools

import dagger.hilt.android.lifecycle.HiltViewModel
import com.xfiles.core.documents.AndroidPdfDocument
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.OpenedPdf
import com.xfiles.core.documents.PdfToolsRepository
import com.xfiles.documentengine.PdfPageEdit
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * One page in the working order. [id] is the page's index in the source and never
 * changes, so it can serve as a stable key while the grid reorders under a drag.
 */
data class PdfReorderSlot(val id: Int, val rotation: Int = 0)

/** The state is the working order of the source pages; nothing is written until [PdfReorderViewModel.apply]. */
@HiltViewModel
class PdfReorderViewModel @Inject constructor(
    tools: PdfToolsRepository,
    documents: DocumentRepository,
    pdf: AndroidPdfDocument,
) : PdfSingleFileToolViewModel<List<PdfReorderSlot>>(emptyList(), tools, documents, pdf) {
    val slots: StateFlow<List<PdfReorderSlot>> get() = tool

    override fun onOpened(opened: OpenedPdf) {
        setTool(List(opened.pageCount) { PdfReorderSlot(it) })
    }

    override fun onReleased() {
        setTool(emptyList())
    }

    /** Moves the slot at [from] to [to]. Out-of-range or no-op moves are ignored. */
    fun move(from: Int, to: Int) {
        val current = slots.value
        if (from == to || from !in current.indices || to !in current.indices) return
        setTool(current.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Turns the page with [id] a quarter turn clockwise. */
    fun turn(id: Int) {
        setTool(slots.value.map { if (it.id == id) it.copy(rotation = (it.rotation + 90) % 360) else it })
    }

    /** Drops the page with [id]. The last page can't be removed. */
    fun remove(id: Int) {
        val current = slots.value
        if (current.size <= 1) return
        setTool(current.filterNot { it.id == id })
    }

    fun resetOrder() {
        val count = openedSource?.pageCount ?: return
        setTool(List(count) { PdfReorderSlot(it) })
    }

    suspend fun pagePreview(id: Int): android.graphics.Bitmap = thumbnail(id, PREVIEW)

    fun apply() {
        if (slots.value.isEmpty()) return
        runTool { opened, progress ->
            listOf(tools.reorder(opened, slots.value.map { PdfPageEdit(it.id, it.rotation) }, progress))
        }
    }

    companion object {
        private const val PREVIEW = 360
    }
}
