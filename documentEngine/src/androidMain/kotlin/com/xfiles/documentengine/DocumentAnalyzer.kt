package com.xfiles.documentengine

import android.content.ContentResolver
import android.net.Uri
import com.xfiles.documents.DocumentAnalysis
import com.xfiles.engine.ffi.NativeLimits
import com.xfiles.engine.ffi.glanceText
import com.xfiles.engine.ffi.textReaderOpenBorrowedFd
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentAnalyzer(private val resolver: ContentResolver) {
    private val readerDispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun extractText(uri: Uri, name: String, maxCharacters: UInt): String = withContext(readerDispatcher) {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("The selected document could not be opened.")
        descriptor.use { source ->
            textReaderOpenBorrowedFd(source.fd, name, NativeLimits(32uL * 1024uL * 1024uL, 0uL, 0uL)).use { reader ->
                reader.read(maxCharacters)
            }
        }
    }

    suspend fun summarize(text: String): DocumentAnalysis = withContext(readerDispatcher) {
        val summary = glanceText(text.take(32_000))
        DocumentAnalysis(
            summary.keySentences,
            summary.keywords,
            summary.language,
            summary.words,
            summary.readingMinutes,
        )
    }
}
