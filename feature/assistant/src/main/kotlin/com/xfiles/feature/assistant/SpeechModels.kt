package com.xfiles.feature.assistant

enum class SpeechState { Idle, Listening, Error, NeedsLanguage }
class SpeechResult(val text: String, val isFinal: Boolean, val alternatives: List<String> = listOf(text))
enum class TtsState { Idle, Speaking, Error }

/** The offline speech pack Google's recognizer is fetching for us. Progress is reported on Android 14 and later. */
sealed interface SpeechPack {
    data object None : SpeechPack
    /** [percent] is null until the system reports one (or never, before Android 14). */
    data class Downloading(val percent: Int?) : SpeechPack
    /** Queued by the system, usually until the phone is on Wi-Fi. */
    data object Scheduled : SpeechPack
    data object Ready : SpeechPack
    data object Failed : SpeechPack
}
