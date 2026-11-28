package com.xfiles.fily

/**
 * A language the phone's on-device speech recognizer can listen in. [installed] languages work offline now;
 * the others can be downloaded by the system ([tag] is a BCP-47 tag such as "en-US").
 */
data class SpeechLanguage(val tag: String, val installed: Boolean)

/** The recognizer's view of its languages: what is installed, what the system can download, and what is pending. */
data class SpeechLanguageSupport(
    val installed: List<SpeechLanguage> = emptyList(),
    val downloadable: List<SpeechLanguage> = emptyList(),
) {
    val all: List<SpeechLanguage> get() = installed + downloadable
}

/** Keys of the Fily preferences (the same store as the assistant's switches). */
object FilyPrefKeys {
    const val SPEECH_LANGUAGE = "speech_language_v1"
}
