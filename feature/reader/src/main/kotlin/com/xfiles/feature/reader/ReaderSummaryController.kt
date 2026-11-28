package com.xfiles.feature.reader

import android.util.Log
import com.xfiles.core.documents.FilePreview
import com.xfiles.feature.reader.domain.DocumentSummaryUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owns the summary job for the current document on the reader's main-thread scope. */
internal class ReaderSummaryController(
    private val summarize: DocumentSummaryUseCase,
    private val state: MutableStateFlow<ReaderState?>,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun show(password: String?) {
        val ready = state.value as? ReaderState.Ready ?: return
        if (ready.summary != null) return
        val shown = when (val preview = ready.preview) {
            is FilePreview.Text -> preview.content
            is FilePreview.Structured -> preview.document.plainText()
            is FilePreview.Html -> preview.text.content
            is FilePreview.Pdf -> null
            else -> return
        }
        if (shown != null && shown.isBlank()) return

        state.value = ready.copy(summary = SummaryState.Loading)
        job = scope.launch {
            val result = try {
                summarize(ready.file, shown, password)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesReader", "Summary failed", error)
                SummaryState.Error
            } catch (error: LinkageError) {
                Log.e("XfilesReader", "Rust analysis library unavailable", error)
                SummaryState.Error
            }
            ensureActive()
            state.update { current ->
                if (current is ReaderState.Ready && current.file.uri == ready.file.uri &&
                    current.summary == SummaryState.Loading
                ) current.copy(summary = result) else current
            }
        }
    }

    fun dismiss() {
        cancel()
        state.update { current ->
            if (current is ReaderState.Ready) current.copy(summary = null) else current
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
