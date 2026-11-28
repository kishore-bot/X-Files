package com.xfiles.feature.reader

import android.net.Uri
import android.util.Log
import com.xfiles.documents.PdfMarkup
import com.xfiles.feature.reader.domain.PdfAnnotateUseCase
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The annotations on the open PDF: the marks made so far, the prepared annotated copy, and its save.
 * Owned by one reader view model; the view model decides when the document allows each step.
 */
class PdfMarkupController @Inject constructor(
    private val annotate: PdfAnnotateUseCase,
) {
    private val mutable = MutableStateFlow(PdfMarkupState())
    val state: StateFlow<PdfMarkupState> = mutable.asStateFlow()
    private var job: Job? = null

    fun add(mark: PdfMarkup) {
        val current = mutable.value
        if (current.working) return
        if (current.marks.size >= MAX_MARKS) {
            mutable.value = current.copy(error = "Save the current annotations before adding more.")
            return
        }
        current.output?.file?.delete()
        mutable.value = PdfMarkupState(marks = current.marks + mark)
    }

    fun undo() {
        val current = mutable.value
        if (current.working || current.marks.isEmpty()) return
        current.output?.file?.delete()
        mutable.value = PdfMarkupState(marks = current.marks.dropLast(1))
    }

    /** Builds the annotated PDF copy from the marks of the file at [source]. */
    fun prepare(scope: CoroutineScope, source: Uri) {
        val current = mutable.value
        if (current.working || current.marks.isEmpty() || current.output != null) return
        mutable.value = current.copy(working = true, error = null)
        job = scope.launch {
            try {
                val output = annotate.prepare(source, current.marks)
                mutable.value = current.copy(output = output, error = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                mutable.value = current.copy(error = error.message ?: "These annotations could not be prepared.")
            } catch (error: Exception) {
                Log.w("XfilesReader", "Could not annotate PDF", error)
                mutable.value = current.copy(error = "These annotations could not be saved to a PDF copy.")
            } catch (error: LinkageError) {
                Log.e("XfilesReader", "Rust PDF markup unavailable", error)
                mutable.value = current.copy(error = "The PDF engine is unavailable on this device.")
            }
        }
    }

    /** Writes the prepared copy to [destination] (the user's chosen place), then removes the temporary copy. */
    fun save(scope: CoroutineScope, destination: Uri) {
        val current = mutable.value
        val output = current.output ?: return
        if (current.working) return
        mutable.value = current.copy(working = true, saving = true, error = null)
        job = scope.launch {
            try {
                annotate.save(output, destination)
                output.file.delete()
                mutable.value = PdfMarkupState(saved = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesReader", "Could not export annotated PDF", error)
                mutable.value = current.copy(error = "The annotated PDF could not be saved here.")
            }
        }
    }

    /** Drops every mark and the temporary copy. */
    fun discard() {
        job?.cancel()
        mutable.value.output?.file?.delete()
        mutable.value = PdfMarkupState()
    }

    /** Removes the temporary copy when the owner goes away. */
    fun release() {
        mutable.value.output?.file?.delete()
    }

    private companion object {
        const val MAX_MARKS = 256
    }
}
