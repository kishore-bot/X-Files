package com.xfiles.core.documents

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.print.PrintAttributes
import android.print.PrintManager
import com.xfiles.documents.DocumentKind

/** Pictures, text and PDFs are laid out as a plain PDF for printing; audio, video and unknown files have no pages. */
fun DocumentKind.canPrint(): Boolean = when (this) {
    DocumentKind.Pdf, DocumentKind.Image, DocumentKind.Text, DocumentKind.Document -> true
    DocumentKind.Audio, DocumentKind.Video, DocumentKind.Unknown -> false
}

/** The outcome of preparing a print outside the reader: a plain PDF to hand to the spooler, or why it can't be made. */
sealed interface PrintRequest {
    class Ready(val pdf: PreparedPdf, val name: String) : PrintRequest
    class Failed(val message: String) : PrintRequest
}

/**
 * Opens the system print screen for [pdf]. Returns false, and deletes the private copy, when no print service
 * can take it. Call on the main thread, with a context that can reach an activity.
 */
fun launchSystemPrint(context: Context, pdf: PreparedPdf, name: String): Boolean {
    val manager = context.findActivity()?.getSystemService(Context.PRINT_SERVICE) as? PrintManager
    if (manager == null) {
        pdf.file.delete()
        return false
    }
    manager.print(
        name.take(120),
        PdfPrintAdapter(pdf, name),
        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build(),
    )
    return true
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
