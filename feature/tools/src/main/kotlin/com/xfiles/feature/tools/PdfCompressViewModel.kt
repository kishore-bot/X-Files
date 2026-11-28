package com.xfiles.feature.tools

import dagger.hilt.android.lifecycle.HiltViewModel
import com.xfiles.core.documents.AndroidPdfDocument
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.OpenedPdf
import com.xfiles.core.documents.PdfToolsRepository
import com.xfiles.documentengine.PdfCompressSummary
import com.xfiles.documents.PdfCompressQuality
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Compress at a chosen [PdfCompressQuality]. Lossless only restructures the file;
 * the lossy levels also bring large images down in resolution.
 */
@HiltViewModel
class PdfCompressViewModel @Inject constructor(
    tools: PdfToolsRepository,
    documents: DocumentRepository,
    pdf: AndroidPdfDocument,
) : PdfSingleFileToolViewModel<Unit>(Unit, tools, documents, pdf) {
    private val mutableSummary = MutableStateFlow<PdfCompressSummary?>(null)
    val summary: StateFlow<PdfCompressSummary?> = mutableSummary.asStateFlow()

    private val mutableQuality = MutableStateFlow(PdfCompressQuality.Balanced)
    val quality: StateFlow<PdfCompressQuality> = mutableQuality.asStateFlow()

    override fun onOpened(opened: OpenedPdf) {
        mutableSummary.value = null
    }

    override fun onReleased() {
        mutableSummary.value = null
    }

    fun setQuality(value: PdfCompressQuality) {
        mutableQuality.update { value }
        mutableSummary.value = null
    }

    fun compress() {
        val level = mutableQuality.value
        runTool { opened, progress ->
            val compressed = tools.compress(opened, level, protectionChoice, progress)
            mutableSummary.value = compressed.summary
            listOf(compressed.prepared)
        }
    }
}
