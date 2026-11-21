package com.xfiles.core.documents

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * On-device OCR with ML Kit's bundled Latin model: nothing is uploaded. ML Kit runs recognition on its
 * own worker threads; this suspends until it finishes and closes the task's result on cancellation.
 */
@Singleton
class TextRecognitionRepository @Inject constructor(@ApplicationContext context: Context) {
    private val app = context.applicationContext
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Recognized lines of [image], blocks separated by blank lines. */
    suspend fun recognize(image: Uri): String {
        val input = withContext(Dispatchers.IO) { InputImage.fromFilePath(app, image) }
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    continuation.resume(result.textBlocks.joinToString("\n\n") { it.text })
                }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }
}
