package com.xfiles.core.documents

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Hands a prepared plain PDF ([ConversionRepository.printable]) to the print spooler unchanged, so text
 * stays vector and nothing is rasterised. The spooler applies page ranges, copies and orientation itself.
 * Writes run on one background thread; [onFinish] deletes the private copy when printing ends.
 */
class PdfPrintAdapter(private val pdf: PreparedPdf, private val name: String) : PrintDocumentAdapter() {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "xfiles-print") }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) return callback.onLayoutCancelled()
        val info = PrintDocumentInfo.Builder(name.take(120))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pdf.pages)
            .build()
        // The document does not depend on the attributes, so it never needs re-laying out.
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        // The framework owns destination; write through a duplicate we close ourselves.
        val target = ParcelFileDescriptor.dup(destination.fileDescriptor)
        val task = writer.submit {
            try {
                FileOutputStream(target.fileDescriptor).use { out ->
                    pdf.file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancellationSignal.isCanceled) return@submit callback.onWriteCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (error: IOException) {
                callback.onWriteFailed(error.message ?: "The document could not be sent to the printer.")
            } finally {
                target.close()
            }
        }
        cancellationSignal.setOnCancelListener { task.cancel(true) }
    }

    override fun onFinish() {
        // Queued behind any write still running, so the copy is never deleted mid-write.
        writer.execute { pdf.file.delete() }
        writer.shutdown()
    }
}
