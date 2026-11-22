package com.xfiles.feature.reader.domain

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A subtitle file the user can add to the playing video. */
data class MediaSubtitleFile(val uri: Uri, val name: String)

/**
 * Subtitle files: their names and types, and the ones sitting in the same folder as the video. Folder listing is
 * best-effort: a provider that won't list children simply yields no suggestions.
 */
class MediaSubtitleFilesUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver = context.contentResolver

    suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Subtitles"
    }

    /** Subtitle files beside [video], found through the document provider's folder listing. */
    suspend fun nearby(video: Uri): List<MediaSubtitleFile> = withContext(Dispatchers.IO) {
        val authority = video.authority ?: return@withContext emptyList()
        val documentId = runCatching { DocumentsContract.getDocumentId(video) }.getOrNull() ?: return@withContext emptyList()
        val folderId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (folderId.isEmpty()) return@withContext emptyList()
        val children = DocumentsContract.buildChildDocumentsUri(authority, folderId)
        try {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < MAX_NEARBY) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        if (subtitleMimeFor(name) != null) add(MediaSubtitleFile(DocumentsContract.buildDocumentUri(authority, id), name))
                    }
                }.sortedBy { it.name.lowercase() }
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        } catch (_: UnsupportedOperationException) {
            emptyList()
        }
    }

    private companion object {
        const val MAX_NEARBY = 50
    }
}

/** The ExoPlayer MIME type for a subtitle file name, or null when the format isn't one we load. */
fun subtitleMimeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "srt" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
    "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
    "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
    "ttml", "dfxp", "xml" -> androidx.media3.common.MimeTypes.APPLICATION_TTML
    else -> null
}
