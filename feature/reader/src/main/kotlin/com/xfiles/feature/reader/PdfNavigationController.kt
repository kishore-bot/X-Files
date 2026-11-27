package com.xfiles.feature.reader

import android.os.SystemClock
import android.util.Log
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.feature.reader.domain.PdfPagesUseCase
import com.xfiles.feature.reader.domain.pdfPartner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Coordinates explicit jumps, scroll updates and spread rendering for one active reader session. */
internal class PdfNavigationController(
    private val pages: PdfPagesUseCase,
    private val state: MutableStateFlow<ReaderState?>,
    private val scope: CoroutineScope,
    private val onPageCommitted: (FileEntry, Int) -> Unit,
) {
    private var jumpId = SystemClock.elapsedRealtimeNanos()
    private var turn: Job? = null
    private var spread: Job? = null

    fun reset() {
        turn?.cancel()
        spread?.cancel()
        turn = null
        spread = null
    }

    fun select(index: Int, explicit: Boolean, password: String?) {
        val ready = state.value as? ReaderState.Ready ?: return
        val pdf = ready.preview as? FilePreview.Pdf ?: return
        if (index !in 0 until pdf.pageCount || (!explicit && ready.turningPage)) return

        val selection = if (explicit) ready.copy(pdfJump = PdfPageJump(++jumpId, index)) else ready
        if (index == pdf.page) {
            if (explicit) {
                turn?.cancel()
                state.value = selection.copy(turningPage = false, pageError = false)
            }
            return
        }
        turn?.cancel()
        spread?.cancel()
        state.value = selection.copy(turningPage = true, pageError = false)
        turn = scope.launch {
            try {
                val page = pages.page(ready.file, index, password)
                val partner = if (ready.twoPage) pdfPartner(index, pdf.pageCount) else null
                val facing = partner?.let { pages.page(ready.file, it, password) }
                ensureActive()
                val active = state.value as? ReaderState.Ready ?: return@launch
                if (active.file.uri != ready.file.uri) return@launch
                state.value = active.copy(preview = page, facing = facing, turningPage = false, pageError = false)
                onPageCommitted(ready.file, index)
                pages.prefetch(scope, ready.file, index, pdf.pageCount, ready.twoPage, password)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                Log.w("XfilesReader", "PDF page could not be read", error)
                val active = state.value as? ReaderState.Ready ?: return@launch
                if (active.file.uri == ready.file.uri) state.value = active.copy(turningPage = false, pageError = true)
            } catch (error: LinkageError) {
                ensureActive()
                Log.e("XfilesReader", "Rust document library unavailable", error)
                state.value = ReaderState.EngineUnavailable
            }
        }
    }

    fun setSpread(enabled: Boolean, password: String?) {
        val ready = state.value as? ReaderState.Ready ?: return
        val pdf = ready.preview as? FilePreview.Pdf ?: return
        if (ready.turningPage || ready.twoPage == enabled) return
        spread?.cancel()
        state.value = ready.copy(twoPage = enabled, facing = null)
        if (enabled) pdfPartner(pdf.page, pdf.pageCount)?.let { partner ->
            spread = scope.launch {
                val facing = try {
                    pages.page(ready.file, partner, password)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("XfilesReader", "Spread page could not be read", error)
                    null
                }
                ensureActive()
                val active = state.value as? ReaderState.Ready ?: return@launch
                if (active.file.uri == ready.file.uri && active.twoPage &&
                    (active.preview as? FilePreview.Pdf)?.page == pdf.page
                ) state.value = active.copy(facing = facing)
            }
        }
        pages.prefetch(scope, ready.file, pdf.page, pdf.pageCount, enabled, password)
    }
}
