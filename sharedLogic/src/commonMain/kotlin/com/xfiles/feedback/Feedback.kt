package com.xfiles.feedback

/** What the user is reporting. [wire] is the value stored in the cloud feedback document. */
enum class FeedbackCategory(val wire: String, val label: String) {
    Bug("bug", "Bug"),
    Idea("idea", "Idea"),
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
    val replyTo: String?,
    val environment: FeedbackEnvironment?,
)

/** Where a sender delivers reports: the team's cloud store, or the system share sheet. */
enum class FeedbackDestination { Cloud, Share }

sealed interface FeedbackResult {
    /** The team's service accepted the report. */
    data object Sent : FeedbackResult

    /** Saved on this phone; it is delivered when a connection is available. */
    data object Queued : FeedbackResult

    /** Handed to the system share sheet; the user finishes sending there. */
    data object Shared : FeedbackResult

    data class Failed(val reason: String) : FeedbackResult
}

/**
 * Delivers feedback. Each build binds one implementation: Firebase builds store reports in Cloud Firestore,
 * every other build opens the share sheet. Nothing is sent without a call to [send] from a user action.
 */
interface FeedbackSender {
    val destination: FeedbackDestination

    suspend fun send(report: FeedbackReport): FeedbackResult
}

object FeedbackRules {
    const val MAX_MESSAGE_LENGTH = 2000
    const val MAX_REPLY_LENGTH = 254

    private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isValidEmail(value: String): Boolean = value.length <= MAX_REPLY_LENGTH && emailPattern.matches(value)

    /** Length in code points, the unit the cloud rules count, so an emoji is one character. */
    fun length(text: String): Int = text.count { !it.isLowSurrogate() }
}

/** The plain-text form used by the share sheet. Mirrors exactly what the cloud document holds. */
fun FeedbackReport.plainText(): String = buildString {
    appendLine("Category: ${category.label}")
    replyTo?.let { appendLine("Reply to: $it") }
    appendLine()
    appendLine(message)
    environment?.let {
        appendLine()
        appendLine(it.summary())
    }
}.trimEnd()
