package com.xfiles.android

import android.net.Uri
import com.xfiles.core.documents.DocumentSharing
import com.xfiles.core.documents.PdfAction
import com.xfiles.feature.reader.DocumentAnalysisViewModel
import com.xfiles.feature.tools.ConvertTool
import com.xfiles.feature.tools.ConvertViewModel
import com.xfiles.feature.tools.PdfToolsViewModel
import com.xfiles.feature.tools.OpenTool
import com.xfiles.fily.FilyDestination
import com.xfiles.fily.FilyHandoff
import com.xfiles.fily.FilyNavigator
import com.xfiles.fily.FilyTidy
import com.xfiles.fily.FilyTidySummary
import com.xfiles.fily.FilyTool
import com.xfiles.tidy.TidyService

/**
 * Fily's hand-offs on Android. Every destination is a screen the user reviews: printing shows the system
 * print preview, bookmarks open the page to bookmark, and PDF tools open with the files filled in but
 * nothing run. Opening a file still refuses while annotations are unsaved.
 */
internal class FilyActions(
    private val navigator: AppNavigator,
    private val library: FilyLibrary,
    private val reader: DocumentAnalysisViewModel,
    private val pdfTools: PdfToolsViewModel,
    private val convert: ConvertViewModel,
    private val tidy: TidyService,
    private val sharing: DocumentSharing,
) : FilyNavigator, FilyTidy {
    override suspend fun go(destination: FilyDestination): FilyHandoff = when (destination) {
        is FilyDestination.Open -> open(destination.documentId, destination.page?.let { (it - 1).coerceAtLeast(0) })
        is FilyDestination.Print -> open(destination.documentId).also { if (it == FilyHandoff.Accepted) reader.print() }
        is FilyDestination.Share -> destination.documentIds.mapNotNull(library::file).let { files ->
            if (files.size == destination.documentIds.size && sharing.share(files.map { it.uri to it.name })) {
                FilyHandoff.Accepted
            } else FilyHandoff.Unavailable
        }
        // Opens the page; the user taps the bookmark button to keep it.
        is FilyDestination.AddBookmark -> open(destination.documentId, destination.page - 1)
        is FilyDestination.Tool -> tool(destination)
    }

    private suspend fun open(id: String, page: Int? = null): FilyHandoff =
        library.open(id, page).also { if (it == FilyHandoff.Accepted) navigator.show(Route.Reader) }

    private fun tool(destination: FilyDestination.Tool): FilyHandoff {
        val uris = destination.documentIds.map(Uri::parse)
        return when (destination.tool) {
            FilyTool.NewText -> {
                val name = destination.draftTitle?.takeIf(String::isNotBlank)?.let { "$it.txt" } ?: "Note.txt"
                if (navigator.requestEditorDraft(name, destination.draftText.orEmpty())) FilyHandoff.Accepted
                else FilyHandoff.AwaitingEditorDecision
            }
            FilyTool.TextToPdf -> convertTool(ConvertTool.TextToPdf, draft = destination.draftText)
            FilyTool.ScanToPdf -> convertTool(ConvertTool.ScanToPdf)
            FilyTool.ImageToText -> convertTool(ConvertTool.PictureToText)
            FilyTool.ImagesToPdf -> convertTool(ConvertTool.PicturesToPdf)
            FilyTool.TidyUp -> {
                navigator.show(Route.Tidy)
                if (destination.startScan) tidy.scan()
                FilyHandoff.Accepted
            }
            FilyTool.PdfTools -> when (destination.pdfOperation) {
                "ToImages" -> convertTool(ConvertTool.PdfToPictures, uris.firstOrNull())
                "ToText" -> convertTool(ConvertTool.PdfToText, uris.firstOrNull())
                "ToZip" -> convertTool(ConvertTool.PdfToZip, uris.firstOrNull())
                else -> {
                    if (pdfTools.state.value.busy || convert.state.value.busy) return FilyHandoff.Busy
                    val action = when (destination.pdfOperation) {
                        "Merge" -> PdfAction.Merge
                        "Split" -> PdfAction.Extract
                        "Organize" -> PdfAction.Rotate
                        "PageSize" -> PdfAction.Resize
                        "AddPassword" -> PdfAction.Protect
                        else -> null
                    }
                    if (action == null) pdfTools.open(null)
                    else if (!pdfTools.prefill(action, uris)) return FilyHandoff.Busy
                    navigator.show(Route.Tools)
                    FilyHandoff.Accepted
                }
            }
        }
    }

    private fun convertTool(tool: ConvertTool, pdf: Uri? = null, draft: String? = null): FilyHandoff {
        if (pdfTools.state.value.busy || !convert.prefill(tool, pdf, draft)) return FilyHandoff.Busy
        pdfTools.open(OpenTool.Convert(tool))
        navigator.show(Route.Tools)
        return FilyHandoff.Accepted
    }

    override suspend fun summary(): FilyTidySummary {
        tidy.load()
        val report = tidy.report.value ?: return FilyTidySummary(available = false)
        return FilyTidySummary(
            available = true,
            duplicateGroups = report.identical.size,
            similarDocumentGroups = report.similarDocuments.size,
            similarPhotoGroups = report.similarPhotos.size,
            reclaimableBytes = report.reclaimableBytes,
            scannedAt = report.scannedAt,
        )
    }
}
