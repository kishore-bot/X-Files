package com.xfiles.core.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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
        return process(input).textBlocks.joinToString("\n\n") { it.text }
    }

    /**
     * Lines of a rendered page with their boxes, in [bitmap] pixels, in reading order of blocks.
     * The caller keeps the bitmap alive until this returns.
     */
    suspend fun lines(bitmap: Bitmap): List<RecognizedLine> =
        process(InputImage.fromBitmap(bitmap, 0)).textBlocks.flatMap { block ->
            block.lines.mapNotNull { line -> line.boundingBox?.let { RecognizedLine(line.text, it) } }
        }

    private suspend fun process(input: InputImage): Text = suspendCancellableCoroutine { continuation ->
        recognizer.process(input)
            .addOnSuccessListener { result -> continuation.resume(result) }
            .addOnFailureListener { error -> continuation.resumeWithException(error) }
    }
}

data class RecognizedLine(val text: String, val box: Rect)
