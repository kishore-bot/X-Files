package com.xfiles.feature.reader.domain

import android.content.Context
import android.net.Uri
import com.xfiles.documentengine.PdfSignatureInfo
import com.xfiles.documentengine.readPdfSignatures
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Reads a PDF's declared signatures off the main thread. An unreadable file has none. */
class ReadPdfSignaturesUseCase @Inject constructor(@ApplicationContext private val context: Context) {
    suspend operator fun invoke(uri: Uri): List<PdfSignatureInfo> = withContext(Dispatchers.IO) {
        try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext emptyList()
            readPdfSignatures(descriptor.detachFd())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
}
