package com.xfiles.feature.assistant

import android.content.Context
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import com.xfiles.fily.SpeechLanguage
import com.xfiles.fily.SpeechLanguageSupport
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The on-device recognizer's languages, for the picker in Fily and About. The recognizer must be created and
 * called from the main thread; the system answers on the main executor, so this suspends without blocking.
 * Below Android 13 (no on-device recognizer API) it reports no languages and the phone's language is used.
 */
suspend fun querySpeechLanguages(context: Context): SpeechLanguageSupport = withContext(Dispatchers.Main) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        return@withContext SpeechLanguageSupport()
    }
    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    try {
        checkSupport(context, recognizer)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: RuntimeException) {
        Log.w(TAG, "Language list unavailable: ${error.message}")
        SpeechLanguageSupport()
    } finally {
        recognizer.destroy()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private suspend fun checkSupport(context: Context, recognizer: SpeechRecognizer): SpeechLanguageSupport =
    suspendCancellableCoroutine { continuation ->
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                continuation.resume(support.toSupport())
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Language check failed ($error)")
                continuation.resume(SpeechLanguageSupport())
            }
        }
        recognizer.checkRecognitionSupport(recognitionIntent(Locale.getDefault().toLanguageTag()), context.mainExecutor, callback)
    }

/**
 * Asks the system to download an offline language pack. The caller keeps the returned recognizer alive until the
 * download finishes (the system shows its own progress); destroying it early cancels nothing already started.
 */
suspend fun triggerSpeechLanguageDownload(context: Context, tag: String): SpeechRecognizer? = withContext(Dispatchers.Main) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        return@withContext null
    }
    try {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { it.triggerModelDownload(recognitionIntent(tag)) }
    } catch (error: RuntimeException) {
        Log.w(TAG, "Language download unavailable: ${error.message}")
        null
    }
}

/** The picker's label for a tag: "English (United States)", in the phone's language. */
fun speechLanguageLabel(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault()).ifBlank { tag }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RecognitionSupport.toSupport(): SpeechLanguageSupport {
    val installed = installedOnDeviceLanguages.toSet()
    val pending = pendingOnDeviceLanguages.toSet()
    return SpeechLanguageSupport(
        installed = installedOnDeviceLanguages.sorted().map { SpeechLanguage(it, installed = true) },
        downloadable = (supportedOnDeviceLanguages.toSet() + pending - installed).sorted().map { SpeechLanguage(it, installed = false) },
    )
}

private fun recognitionIntent(tag: String) = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
}

private const val TAG = "EzSpeech"
