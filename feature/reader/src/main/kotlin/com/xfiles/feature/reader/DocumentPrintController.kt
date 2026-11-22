package com.xfiles.feature.reader

import android.util.Log
import com.xfiles.core.documents.ConversionRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.UnprintableDocument
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Print hand-offs: prepares a printable copy and sends it to the screen, which owns the Activity the system
 * print UI needs. [requests] carries the copies; [status] says what the reader should show about them.
 */
class DocumentPrintController @Inject constructor(
    private val conversions: ConversionRepository,
) {
    private val jobs = Channel<PrintJob>(Channel.BUFFERED)
    val requests: Flow<PrintJob> = jobs.receiveAsFlow()
    private val mutable = MutableStateFlow<PrintStatus>(PrintStatus.Idle)
    val status: StateFlow<PrintStatus> = mutable.asStateFlow()
    private var job: Job? = null

    fun print(scope: CoroutineScope, file: FileEntry, password: String?) {
        if (job?.isActive == true) return
        job = scope.launch {
            mutable.value = PrintStatus.Preparing
            mutable.value = try {
                jobs.send(PrintJob(conversions.printable(file, password), file.name))
                PrintStatus.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UnprintableDocument) {
                PrintStatus.Failed(error.message.orEmpty())
            } catch (error: IllegalArgumentException) {
                PrintStatus.Failed(error.message ?: "This document can't be printed.")
            } catch (error: Exception) {
                Log.w("XfilesReader", "Print preparation failed", error)
                PrintStatus.Failed("This document could not be prepared for printing.")
            }
        }
    }

    /** Shows [message] as the print status, e.g. when sharing has no app to take the file. */
    fun fail(message: String, title: String) {
        mutable.value = PrintStatus.Failed(message, title)
    }

    fun dismiss() {
        mutable.value = PrintStatus.Idle
    }

    /** Stops a copy in progress and clears the status, when the document closes. */
    fun cancel() {
        job?.cancel()
        mutable.value = PrintStatus.Idle
    }
}
