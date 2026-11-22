package com.xfiles.feature.reader.domain

import android.content.Context
import com.xfiles.documents.structure.DocumentParts
import com.xfiles.documents.structure.SlideShape
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Media a slide can play: a file extracted to the cache, or a URL to stream. */
internal sealed interface SlideMedia {
    val audio: Boolean

    data class Stream(val url: String, override val audio: Boolean) : SlideMedia

    data class Local(val file: File, override val audio: Boolean) : SlideMedia
}

/**
 * Makes a shape's media playable. Linked media is streamed by URL. Embedded media is copied out of the
 * presentation into the cache once (the name is a hash of its part path, so repeat taps reuse the copy).
 */
internal class ResolveSlideMediaUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend operator fun invoke(shape: SlideShape, parts: DocumentParts?): SlideMedia = withContext(Dispatchers.IO) {
        val path = shape.media ?: throw IOException("This shape has no media")
        if (shape.mediaExternal) return@withContext SlideMedia.Stream(path, shape.mediaAudio)
        val reader = parts ?: throw IOException("This presentation's media can't be read")
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val extension = path.substringAfterLast('/').substringAfterLast('.', "bin").lowercase().take(5)
        val file = File(dir, "${sha256(path)}.$extension")
        if (!file.isFile || file.length() == 0L) {
            val partial = File(dir, "${file.name}.part")
            partial.writeBytes(reader.read(path))
            if (!partial.renameTo(file)) {
                partial.copyTo(file, overwrite = true)
                partial.delete()
            }
        }
        SlideMedia.Local(file, shape.mediaAudio)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CACHE_DIR = "slide-media"
    }
}
