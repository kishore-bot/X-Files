package com.xfiles.feature.reader.domain

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How a save ended. */
sealed interface SaveResult {
    data class Saved(val name: String) : SaveResult

    /** Below API 29 the copy needs WRITE_EXTERNAL_STORAGE; the caller asks for it and retries. */
    data object NeedsStoragePermission : SaveResult

    data class Failed(val reason: String) : SaveResult
}

/**
 * Copies a document opened from another app (a `content://` URI outside device storage) into the public
 * Downloads folder. API 29+ writes through MediaStore.Downloads; older systems write the file directly.
 */
class SaveToDownloadsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** [onProgress] receives a fraction in 0..1 when the source size is known. */
    suspend operator fun invoke(source: Uri, name: String, onProgress: (Float) -> Unit = {}): SaveResult =
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(source, name, onProgress)
                else saveToLegacyDownloads(source, name, onProgress)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                SaveResult.Failed(e.message ?: "The file could not be saved.")
            }
        }

    private suspend fun saveWithMediaStore(source: Uri, name: String, onProgress: (Float) -> Unit): SaveResult {
        val resolver = context.contentResolver
        val mime = resolver.getType(source) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return SaveResult.Failed("Downloads is not available.")
        try {
            val out = resolver.openOutputStream(target) ?: throw IllegalStateException("Downloads is not available.")
            out.use { copy(source, it, onProgress) }
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            // Includes cancellation: no pending row is left behind in Downloads.
            resolver.delete(target, null, null)
            throw e
        }
        return SaveResult.Saved(name)
    }

    private suspend fun saveToLegacyDownloads(source: Uri, name: String, onProgress: (Float) -> Unit): SaveResult {
        val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return SaveResult.NeedsStoragePermission
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return SaveResult.Failed("Downloads is not available.")
        val file = uniqueFile(dir, name)
        try {
            FileOutputStream(file).use { copy(source, it, onProgress) }
        } catch (e: Exception) {
            // Includes cancellation: a partial copy is not left in Downloads.
            file.delete()
            throw e
        }
        return SaveResult.Saved(file.name)
    }

    private suspend fun copy(source: Uri, out: java.io.OutputStream, onProgress: (Float) -> Unit) {
        val resolver = context.contentResolver
        val total = resolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
        resolver.openInputStream(source)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                copied += read
                if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
            }
        } ?: throw IllegalStateException("The file could not be read.")
        onProgress(1f)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) candidate = File(dir, "$base ($n)$ext").also { n++ }
        return candidate
    }
}

/**
 * True when [uri] already lives in storage the user can see in Downloads, so no copy is needed. Other
 * apps' providers (email attachments, chat, cloud) are not device storage and get the save action.
 */
fun isInDeviceStorage(uri: Uri): Boolean = when (uri.scheme) {
    "file" -> true
    "content" -> uri.authority in DEVICE_STORAGE_AUTHORITIES
    else -> false
}

private val DEVICE_STORAGE_AUTHORITIES = setOf(
    "com.android.externalstorage.documents",
    "com.android.providers.downloads.documents",
    "media",
    "com.android.providers.media.documents",
)
