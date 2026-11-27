package com.xfiles.feedback

/** What the user is reporting. [wire] is the value stored in the cloud feedback document. */
enum class FeedbackCategory(val wire: String, val label: String) {
    Bug("bug", "Bug"),
    Idea("idea", "Suggestion"),
    Other("other", "Other"),
}

/** Non-personal facts about this install. Attached to a report only when the user keeps the option on. */
data class FeedbackEnvironment(
    val appVersion: String,
    val versionCode: Long,
    val deviceModel: String,
    val androidSdk: Int,
    val flavor: String,
    val locale: String,
) {
    /** The exact text shown to the user as "what will be attached". */
    fun summary(): String = "App $appVersion ($versionCode), $deviceModel, Android API $androidSdk, $flavor build, $locale"
}

/** One report. [environment] is null when the user declined to attach device details. */
data class FeedbackReport(
    val category: FeedbackCategory,
    val message: String,
    val environment: FeedbackEnvironment?,
)

sealed interface FeedbackResult {
    /** The team's service accepted the report. */
    data object Sent : FeedbackResult

    /** Saved on this phone; it is delivered when a connection is available. */
    data object Queued : FeedbackResult

    data class Failed(val reason: String) : FeedbackResult
}

/**
 * Firestore-enabled builds store reports in Cloud Firestore. [send] is called from a user action.
 */
interface FeedbackSender {
    val available: Boolean

    suspend fun send(report: FeedbackReport): FeedbackResult
}

object FeedbackRules {
    const val MAX_MESSAGE_LENGTH = 2000
    /** Length in code points, the unit the cloud rules count, so an emoji is one character. */
    fun length(text: String): Int = text.count { !it.isLowSurrogate() }
}
