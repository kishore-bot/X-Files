package com.xfiles.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.xfiles.core.documents.PdfPrintAdapter
import com.xfiles.core.documents.PreparedPdf
import kotlinx.coroutines.flow.Flow

/** A prepared plain PDF ready for the system print UI. */
class PrintJob(val pdf: PreparedPdf, val name: String)

sealed interface PrintStatus {
    data object Idle : PrintStatus
    data object Preparing : PrintStatus
    data class Failed(val message: String, val title: String = "Can't print") : PrintStatus
}

/** Opens the system print screen for each [PrintJob]; collected only while the screen is started. */
@Composable
internal fun PrintRequests(requests: Flow<PrintJob>) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(requests, context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            requests.collect { job ->
                val activity = context.findActivity()
                val manager = activity?.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (manager == null) {
                    job.pdf.file.delete()
                } else {
                    manager.print(
                        job.name.take(120),
                        PdfPrintAdapter(job.pdf, job.name),
                        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build(),
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
