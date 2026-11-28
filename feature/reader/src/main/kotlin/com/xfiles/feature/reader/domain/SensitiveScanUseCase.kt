package com.xfiles.feature.reader.domain

import android.util.Log
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.toTidyFile
import com.xfiles.documents.DocumentKind
import com.xfiles.tidy.SensitiveCheck
import com.xfiles.tidy.SensitiveFindings
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/** Checks the open document for sensitive numbers once it has settled on screen. */
class SensitiveScanUseCase @Inject constructor(
    private val sensitiveCheck: SensitiveCheck,
) {
    /**
     * Findings for each file the [files] flow shows; null while checking, and for kinds that are not scanned.
     * transformLatest cancels the check for a document that was closed or replaced.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(files: Flow<FileEntry?>): Flow<SensitiveFindings?> = files
        .distinctUntilChanged()
        .transformLatest { file ->
            emit(null)
            if (file == null || file.kind !in SCANNED_KINDS) return@transformLatest
            delay(SETTLE_MS) // Let the document draw first; opening is never slower.
            val found = try {
                sensitiveCheck.check(file.toTidyFile())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesReader", "Sensitive check failed", error)
                null
            }
            emit(found)
        }

    private companion object {
        val SCANNED_KINDS = setOf(DocumentKind.Pdf, DocumentKind.Document, DocumentKind.Text)
        const val SETTLE_MS = 1_200L
    }
}
