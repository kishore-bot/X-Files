package com.xfiles.android

import android.net.Uri
import com.xfiles.core.documents.DocumentSharing
import com.xfiles.core.documents.PdfAction
import com.xfiles.feature.reader.DocumentAnalysisViewModel
import com.xfiles.feature.tools.ConvertTool
import com.xfiles.feature.tools.ConvertViewModel
import com.xfiles.feature.tools.PdfToolsViewModel
import com.xfiles.fily.FilyDestination
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
    override suspend fun go(destination: FilyDestination): Boolean = when (destination) {
        is FilyDestination.Open -> library.open(destination.documentId, ((destination.page ?: 1) - 1).coerceAtLeast(0))
        is FilyDestination.Print -> library.open(destination.documentId).also { opened -> if (opened) reader.print() }
        is FilyDestination.Share -> destination.documentIds.mapNotNull(library::file).let { files ->
            files.size == destination.documentIds.size && sharing.share(files.map { it.uri to it.name })
        }
        // Opens the page; the user taps the bookmark button to keep it.
        is FilyDestination.AddBookmark -> library.open(destination.documentId, destination.page - 1)
        is FilyDestination.Tool -> {
            tool(destination)
            true
        }
    }

    private fun tool(destination: FilyDestination.Tool) {
        val uris = destination.documentIds.map(Uri::parse)
        when (destination.tool) {
            FilyTool.NewText -> navigator.openEditor(
                destination.draftTitle?.takeIf(String::isNotBlank)?.let { "$it.txt" } ?: "Note.txt",
                destination.draftText.orEmpty(),
            )
            FilyTool.TextToPdf -> convertTool(ConvertTool.TextToPdf, draft = destination.draftText)
            FilyTool.ScanToPdf -> convertTool(ConvertTool.ScanToPdf)
            FilyTool.ImageToText -> convertTool(ConvertTool.PictureToText)
            FilyTool.ImagesToPdf -> convertTool(ConvertTool.PicturesToPdf)
            FilyTool.TidyUp -> {
                navigator.show(Route.Tidy)
                if (destination.startScan) tidy.scan()
            }
            FilyTool.PdfTools -> when (destination.pdfOperation) {
                "ToImages" -> convertTool(ConvertTool.PdfToPictures, uris.firstOrNull())
                "ToText" -> convertTool(ConvertTool.PdfToText, uris.firstOrNull())
                "ToZip" -> convertTool(ConvertTool.PdfToZip, uris.firstOrNull())
                else -> {
                    val action = when (destination.pdfOperation) {
                        "Merge" -> PdfAction.Merge
                        "Split" -> PdfAction.Extract
                        "Organize" -> PdfAction.Rotate
                        "PageSize" -> PdfAction.Resize
                        "AddPassword" -> PdfAction.Protect
                        else -> null
                    }
                    if (action != null) pdfTools.prefill(action, uris) else pdfTools.open(null)
                    navigator.show(Route.Tools)
                }
            }
        }
    }

    private fun convertTool(tool: ConvertTool, pdf: Uri? = null, draft: String? = null) {
        convert.prefill(tool, pdf, draft)
        pdfTools.open(com.xfiles.feature.tools.OpenTool.Convert(tool))
        navigator.show(Route.Tools)
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
