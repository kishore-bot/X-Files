package com.xfiles.feature.reader.domain

import android.net.Uri
import com.xfiles.core.documents.PdfToolsRepository
import com.xfiles.core.documents.PreparedPdf
import com.xfiles.documents.PdfMarkup
import javax.inject.Inject

/** Builds the annotated PDF copy from the marks, and writes that copy where the user chose. */
class PdfAnnotateUseCase @Inject constructor(
    private val tools: PdfToolsRepository,
) {
    suspend fun prepare(source: Uri, marks: List<PdfMarkup>): PreparedPdf = tools.annotate(source, marks)

    suspend fun save(output: PreparedPdf, destination: Uri) {
        tools.save(output, destination)
    }
}
