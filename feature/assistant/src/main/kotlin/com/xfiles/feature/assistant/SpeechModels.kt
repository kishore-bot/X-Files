package com.xfiles.feature.assistant

enum class SpeechState { Idle, Listening, Error, NeedsLanguage }
class SpeechResult(val text: String, val isFinal: Boolean, val alternatives: List<String> = listOf(text))
enum class TtsState { Idle, Speaking, Error }
