package com.xfiles.feature.assistant

import android.content.Context
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        })
    }

    fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, AndroidTts.QUEUE_FLUSH, null, UUID.randomUUID().toString())
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
