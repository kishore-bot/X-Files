package com.xfiles.feature.tools

import android.graphics.Bitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xfiles.core.documents.AndroidPdfDocument
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.PdfToolsRepository
import com.xfiles.documents.PdfWatermarkSpec
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class PdfWatermarkViewModel @Inject constructor(
    tools: PdfToolsRepository,
    documents: DocumentRepository,
    pdf: AndroidPdfDocument,
) : PdfSingleFileToolViewModel<PdfWatermarkSpec>(PdfWatermarkSpec(text = "CONFIDENTIAL"), tools, documents, pdf) {
    val spec: StateFlow<PdfWatermarkSpec> get() = tool

    /** Replaces the spec with [transform]'s result. The preview redraws from the new value. */
    fun edit(transform: (PdfWatermarkSpec) -> PdfWatermarkSpec) {
        setTool(transform(tool.value))
    }

    /** Applies the spec to the chosen pages. An invalid spec is reported, not run. */
    fun apply() {
        val current = tool.value
        current.validationError()?.let { problem ->
            mutateFrame { it.copy(error = problem) }
            return
        }
        runTool { opened, progress -> listOf(tools.watermark(opened, current, protectionChoice, progress)) }
    }

    suspend fun pagePreview(page: Int): Bitmap = thumbnail(page, PREVIEW)

    companion object {
        private const val PREVIEW = 720
    }
}
