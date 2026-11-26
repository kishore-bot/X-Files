package com.xfiles.feature.assistant

import android.content.Context
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID
import android.speech.tts.TextToSpeech as AndroidTts

class TextToSpeech(context: Context) {
    private val _state = MutableStateFlow(TtsState.Idle)
    val state: StateFlow<TtsState> = _state

    private var tts: AndroidTts? = null
    private var ready = false

    init {
        tts = AndroidTts(context) { status ->
            ready = status == AndroidTts.SUCCESS
            if (!ready) _state.value = TtsState.Error
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _state.value = TtsState.Speaking }
            override fun onDone(utteranceId: String?) { _state.value = TtsState.Idle }
            @Deprecated("Deprecated") override fun onError(utteranceId: String?) { _state.value = TtsState.Error }
            override fun onError(utteranceId: String?, errorCode: Int) { _state.value = TtsState.Error }
        })
    }

    /**
     * Speaks [text] in [language] (BCP-47) when the phone has a voice for it; otherwise the phone's default voice,
     * so the reply is never silent just because a voice is missing.
     */
    fun speak(text: String, language: String? = null) {
        if (!ready) return
        val engine = tts ?: return
        val preferred = language?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        val voices = engine.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty()
        }
        val voice = voices.firstOrNull { it.locale == preferred }
            ?: voices.firstOrNull { it.locale.language == preferred.language }
            ?: voices.firstOrNull { it.locale == Locale.getDefault() }
        if (voice == null || engine.setVoice(voice) != AndroidTts.SUCCESS ||
            engine.speak(text, AndroidTts.QUEUE_FLUSH, null, UUID.randomUUID().toString()) != AndroidTts.SUCCESS
        ) {
            _state.value = TtsState.Error
        }
    }

    fun stop() {
        tts?.stop()
        _state.value = TtsState.Idle
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

@Composable
fun rememberTextToSpeech(): TextToSpeech {
    val context = LocalContext.current
    val tts = remember(context) { TextToSpeech(context) }
    DisposableEffect(tts) { onDispose { tts.destroy() } }
    return tts
}
