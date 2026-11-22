package com.xfiles.feature.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class SpeechRecognition(
    private val context: Context,
    private val hints: () -> List<String>,
    /** The language the user picked (BCP-47), or null to use the phone's English. Read at each start. */
    private val preferredLanguage: () -> String?,
    private val onResult: (SpeechResult) -> Unit,
) {
    private val _state = MutableStateFlow(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _pack = MutableStateFlow<SpeechPack>(SpeechPack.None)
    val pack: StateFlow<SpeechPack> = _pack

    /** No recognizer at all: Google's speech services are missing or disabled on this phone. */
    val engineMissing: Boolean
        get() = !onDevice && !SpeechRecognizer.isRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null
    /** Kept alive while the system downloads a language pack for us. */
    private var downloader: SpeechRecognizer? = null

    /**
     * The English the offline recognizer has installed (the phone's own English if it is there,
     * else any English: en-US, en-GB, en-IN…). Null until checked.
     */
    private var language: String? = null

    /**
     * The system's default recognizer may stream audio to a server. Xfiles is offline, so the
     * on-device recognizer is used wherever the phone has one, and the default one is told to
     * stay offline otherwise.
     */
    private val onDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start() {
        cancel()
        val local = onDevice
        if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = SpeechState.Error
            return
        }
        val sr = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        // Listening from now on, so the overlay doesn't start us again while the language is checked.
        _state.value = SpeechState.Listening
        val chosen = preferredLanguage()
        if (chosen != null) {
            // The user's pick wins over the phone's English; a missing pack comes back as NeedsLanguage.
            language = chosen
            listen(sr, local)
        } else if (local && language == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkLanguage(sr)
        } else {
            listen(sr, local)
        }
    }

    /** Finds an installed English before the first session; with none, asks for a download instead of failing silently. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkLanguage(sr: SpeechRecognizer) {
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                if (recognizer !== sr) return
                val chosen = englishIn(support)
                Log.i(TAG, "Offline languages: installed=${support.installedOnDeviceLanguages} pending=${support.pendingOnDeviceLanguages} -> $chosen")
                if (chosen == null) {
                    needsLanguage()
                } else {
                    language = chosen
                    listen(sr, local = true)
                }
            }

            override fun onError(error: Int) {
                if (recognizer !== sr) return
                Log.w(TAG, "Language check failed ($error); trying the phone's language")
                language = Locale.getDefault().toLanguageTag()
                listen(sr, local = true)
            }
        }
        try {
            sr.checkRecognitionSupport(baseIntent(Locale.getDefault().toLanguageTag()), context.mainExecutor, callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Language check unavailable: ${e.message}")
            language = Locale.getDefault().toLanguageTag()
            listen(sr, local = true)
        }
    }

    private fun listen(sr: SpeechRecognizer, local: Boolean) {
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = SpeechState.Listening }
            override fun onBeginningOfSpeech() {}
            // Roughly -2 dB (silence) to 10 dB (speech) on phones.
            override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _level.value = 0f }
            override fun onError(error: Int) {
                Log.w(TAG, "Recognition error $error (on-device=$local, language=$language)")
                if (local && (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE)) needsLanguage()
                else _state.value = SpeechState.Error
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter(String::isNotBlank)
                Log.i(TAG, "Heard (on-device=$local): $matches")
                if (matches.isNotEmpty()) onResult(SpeechResult(matches.first(), true, matches))
                _state.value = SpeechState.Idle
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onResult(SpeechResult(text, false))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = baseIntent(language ?: Locale.getDefault().toLanguageTag()).apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Several guesses: the assistant keeps the one that names a file or a command.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Room for a short pause mid-sentence ("open the... resume").
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            if (local && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints().take(MAX_HINTS)))
            }
        }
        sr.startListening(intent)
        _state.value = SpeechState.Listening
    }

    private fun needsLanguage() {
        language = null
        recognizer?.destroy()
        recognizer = null
        _level.value = 0f
        _state.value = SpeechState.NeedsLanguage
    }

    /**
     * Asks Google's recognizer for the offline pack of [tag] (else the picked language, else the phone's English).
     * On Android 14+ the download reports progress through [pack]; earlier, the system shows its own prompt.
     */
    fun downloadLanguage(tag: String? = null) {
        language = null
        val wanted = tag ?: preferredLanguage()
            ?: Locale.getDefault().toLanguageTag().takeIf { it.startsWith("en", ignoreCase = true) } ?: "en-US"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            try {
                downloader?.destroy()
                val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                downloader = sr
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    _pack.value = SpeechPack.Downloading(null)
                    sr.triggerModelDownload(baseIntent(wanted), context.mainExecutor, packListener(wanted))
                } else {
                    sr.triggerModelDownload(baseIntent(wanted))
                }
                _state.value = SpeechState.Idle
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "Language download unavailable: ${e.message}")
                _pack.value = SpeechPack.None
            }
        }
        // Otherwise the phone's voice input settings, where an offline language can be added.
        for (action in listOf(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS, android.provider.Settings.ACTION_SETTINGS)) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                break
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
        _state.value = SpeechState.Idle
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun packListener(tag: String) = object : android.speech.ModelDownloadListener {
        override fun onProgress(completedPercent: Int) { _pack.value = SpeechPack.Downloading(completedPercent.coerceIn(0, 100)) }
        override fun onSuccess() {
            Log.i(TAG, "Speech pack $tag ready")
            _pack.value = SpeechPack.Ready
        }
        override fun onScheduled() { _pack.value = SpeechPack.Scheduled }
        override fun onError(error: Int) {
            Log.w(TAG, "Speech pack $tag failed ($error)")
            _pack.value = SpeechPack.Failed
        }
    }

    /** The pack result has been shown; back to no download. */
    fun packSeen() { _pack.value = SpeechPack.None }

    /** Opens Google's speech services in the Play Store (the store app, else the web page). */
    fun getSpeechEngine() {
        val store = listOf("market://details?id=$GOOGLE_SPEECH", "https://play.google.com/store/apps/details?id=$GOOGLE_SPEECH")
        for (uri in store) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        if (_state.value != SpeechState.NeedsLanguage) _state.value = SpeechState.Idle
    }

    fun destroy() {
        cancel()
        downloader?.destroy()
        downloader = null
    }

    private fun baseIntent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    }

    private companion object {
        const val TAG = "EzSpeech"
        const val MAX_HINTS = 100
        /** "Speech Recognition & Synthesis from Google": the on-device recognizer and its language packs. */
        const val GOOGLE_SPEECH = "com.google.android.tts"
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31).
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}

/**
 * The English to listen in: the phone's own if it is installed, else any installed English.
 * Null when the recognizer reports languages but no English among the installed ones. A
 * recognizer that reports nothing at all is trusted with the phone's language.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun englishIn(support: RecognitionSupport): String? {
    val installed = support.installedOnDeviceLanguages
    val phone = Locale.getDefault().toLanguageTag()
    if (installed.isEmpty() && support.pendingOnDeviceLanguages.isEmpty() && support.supportedOnDeviceLanguages.isEmpty()) return phone
    return pickEnglish(phone, installed)
}

/** [phone] if it is an installed English, else the first installed English (US first), else null. */
internal fun pickEnglish(phone: String, installed: List<String>): String? {
    val english = installed.filter { it.lowercase().let { tag -> tag == "en" || tag.startsWith("en-") || tag.startsWith("en_") } }
    return english.firstOrNull { it.equals(phone, ignoreCase = true) && phone.startsWith("en", ignoreCase = true) }
        ?: english.firstOrNull { it.equals("en-US", ignoreCase = true) }
        ?: english.firstOrNull()
}

@Composable
fun rememberSpeechRecognition(
    hints: () -> List<String>,
    preferredLanguage: () -> String? = { null },
    onResult: (SpeechResult) -> Unit,
): SpeechRecognition {
    val context = LocalContext.current
    val latestHints by rememberUpdatedState(hints)
    val latestLanguage by rememberUpdatedState(preferredLanguage)
    val sr = remember(context) { SpeechRecognition(context, { latestHints() }, { latestLanguage() }, onResult) }
    DisposableEffect(sr) { onDispose { sr.destroy() } }
    return sr
}
