package com.xfiles.documentengine

import android.content.ContentResolver
import android.net.Uri
import android.os.Trace
import com.xfiles.documents.DocumentAnalysis
import com.xfiles.documents.GlanceItem
import com.xfiles.documents.GlanceKind
import com.xfiles.engine.ffi.NativeGlanceItem
import com.xfiles.engine.ffi.NativeLimits
import com.xfiles.engine.ffi.glanceText
import com.xfiles.engine.ffi.textReaderOpenBorrowedFd
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DocumentAnalyzer(private val resolver: ContentResolver) {
    private val readerDispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun extractText(uri: Uri, name: String, maxCharacters: UInt): String = withContext(readerDispatcher) {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("The selected document could not be opened.")
        descriptor.use { source ->
            val job = currentCoroutineContext()
            job.ensureActive()
            val tracing = Trace.isEnabled()
            if (tracing) Trace.beginSection("XfilesNativeTextRead")
            try {
                textReaderOpenBorrowedFd(source.fd, name, NativeLimits(32uL * 1024uL * 1024uL, 0uL, 0uL)).use { reader ->
                    val text = reader.read(maxCharacters)
                    job.ensureActive()
                    if (tracing) Trace.setCounter("XfilesNativeTextChars", text.length.toLong())
                    text
                }
            } finally {
                if (tracing) Trace.endSection()
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
            summary.items.map { it.toGlanceItem() },
        )
    }

    /** The engine's kinds mirror [GlanceKind] by name; matched case-insensitively so the binding's naming does not matter. */
    private fun NativeGlanceItem.toGlanceItem() = GlanceItem(
        kind = GlanceKind.entries.firstOrNull { it.name.equals(kind.name, ignoreCase = true) } ?: GlanceKind.Reference,
        text = text,
        value = value,
        label = label,
    )
}
