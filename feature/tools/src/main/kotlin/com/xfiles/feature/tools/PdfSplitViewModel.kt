package com.xfiles.feature.tools

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xfiles.core.documents.AndroidPdfDocument
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.PdfToolsRepository
import com.xfiles.documents.PdfPageSpan
import com.xfiles.documents.PdfSplitPlan
import com.xfiles.documents.pdfSplitPartName
import javax.inject.Inject

/** The chosen split plan, and any part names the user changed. Names are keyed by part index. */
data class PdfSplitInput(
    val plan: PdfSplitPlan = PdfSplitPlan.Ranges(""),
    val names: Map<Int, String> = emptyMap(),
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

    /** Changing the plan, or its text, resets custom names, since the part numbers may no longer match. */
    fun setPlan(plan: PdfSplitPlan) {
        setTool(PdfSplitInput(plan = plan))
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
}
