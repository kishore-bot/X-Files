package com.xfiles.feature.reader.domain

import com.xfiles.documents.structure.DocumentParts
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The full-size bytes of a picture for the zoom viewer. The preloaded copy is used when the reader kept
 * one; otherwise the part is read again from the file, so a high-resolution picture is never cut short.
 */
internal class LoadSlidePictureUseCase @Inject constructor() {
    suspend operator fun invoke(path: String, preloaded: Map<String, ByteArray>, parts: DocumentParts?): ByteArray =
        withContext(Dispatchers.IO) {
            preloaded[path] ?: (parts ?: throw IOException("This picture can't be read")).read(path)
        }
}
