package com.xfiles.feature.tools

import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xfiles.core.documents.AndroidPdfDocument
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.PdfToolsRepository
import com.xfiles.documents.PdfPageSpan
import com.xfiles.documents.PdfSplitPlan
import com.xfiles.documents.pdfSplitPartName
import javax.inject.Inject

/**
 * The chosen split plan, the cut points of a custom split (zero-based: a part ends after each cut page), and any
 * part names the user changed. Names are keyed by part index.
 */
data class PdfSplitInput(
    val plan: PdfSplitPlan = PdfSplitPlan.Ranges(""),
    val names: Map<Int, String> = emptyMap(),
    val cuts: Set<Int> = emptySet(),
)

@HiltViewModel
class PdfSplitViewModel @Inject constructor(
    tools: PdfToolsRepository,
    documents: DocumentRepository,
    pdf: AndroidPdfDocument,
) : PdfSingleFileToolViewModel<PdfSplitInput>(PdfSplitInput(), tools, documents, pdf) {
    /** Spans of the last run, so names line up with the outputs when saving. */
    private var lastSpans: List<PdfPageSpan> = emptyList()

    override fun onReleased() {
        lastSpans = emptyList()
    }

    /** Changing the plan, or its text, resets custom names and cuts, since the part numbers may no longer match. */
    fun setPlan(plan: PdfSplitPlan) {
        setTool(PdfSplitInput(plan = plan))
    }

    /** Switches to a custom split that starts from [cuts], the cuts the current parts already show. */
    fun startCustom(cuts: Set<Int>) {
        val pages = frame.value.pageCount
        setTool(PdfSplitInput(plan = PdfSplitPlan.Ranges(rangesFromCuts(cuts, pages)), cuts = cuts))
    }

    /** Custom split: a part ends after [page] (zero-based), or the cut is removed. The plan's text follows the cuts. */
    fun toggleCut(page: Int) {
        val pages = frame.value.pageCount
        if (page !in 0 until pages - 1) return
        val current = tool.value
        val cuts = if (page in current.cuts) current.cuts - page else current.cuts + page
        setTool(PdfSplitInput(plan = PdfSplitPlan.Ranges(rangesFromCuts(cuts, pages)), cuts = cuts))
    }

    fun setName(index: Int, name: String) {
        val current = tool.value
        setTool(current.copy(names = current.names + (index to name)))
    }

    /** The names for [spans]: the user's where set, otherwise the default. */
    fun partNames(spans: List<PdfPageSpan>): List<String> {
        val base = frame.value.name
        val names = tool.value.names
        return spans.mapIndexed { index, span -> names[index] ?: pdfSplitPartName(base, index, span) }
    }

    /** A page thumbnail for the split grid. */
    suspend fun pagePreview(page: Int): Bitmap = thumbnail(page, GRID_EDGE)

    fun split(spans: List<PdfPageSpan>) {
        if (spans.isEmpty()) return
        lastSpans = spans
        runTool { opened, progress -> tools.split(opened, spans, progress) }
    }

    /** Writes every part into [folder], under the names from [partNames]. */
    fun saveParts(folder: Uri) {
        val names = partNames(lastSpans)
        persist { outputs -> tools.saveAll(outputs, names, folder) }
    }

    private companion object {
        const val GRID_EDGE = 300
    }
}

/** The page-range text for [cuts] over [pageCount] pages, for example "1-3, 4-7, 8-10". */
internal fun rangesFromCuts(cuts: Set<Int>, pageCount: Int): String {
    val parts = ArrayList<String>()
    var start = 0
    for (page in 0 until pageCount) {
        if (page in cuts || page == pageCount - 1) {
            parts += if (start == page) "${start + 1}" else "${start + 1}-${page + 1}"
            start = page + 1
        }
    }
    return parts.joinToString(", ")
}
