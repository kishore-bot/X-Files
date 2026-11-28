package com.xfiles.android.feedback

import com.google.firebase.firestore.FieldValue
import com.xfiles.android.FirestoreReports
import com.xfiles.feedback.FeedbackReport
import com.xfiles.feedback.FeedbackResult
import com.xfiles.feedback.FeedbackSender
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

internal class FirestoreFeedbackSender @Inject constructor(
    private val firestore: FirestoreReports,
) : FeedbackSender {
    override val available = true

    override suspend fun send(report: FeedbackReport): FeedbackResult {
        // Lengths match firebase/firestore.rules: an oversized field rejects the whole write, and an offline write
        // is only rejected later, where no one sees it.
        val document = buildMap<String, Any> {
            put("category", report.category.wire)
            put("message", report.message.take(MAX_MESSAGE))
            put("createdAt", FieldValue.serverTimestamp())
            report.environment?.let { env ->
                put("appVersion", env.appVersion.take(MAX_APP_VERSION))
                put("versionCode", env.versionCode.coerceAtLeast(0))
                put("deviceModel", env.deviceModel.take(MAX_DEVICE_MODEL))
                put("androidSdk", env.androidSdk.coerceAtLeast(0))
                put("flavor", env.flavor.take(MAX_FLAVOR))
                put("locale", env.locale.take(MAX_LOCALE))
            }
        }
        return firestore.sendFeedback(document)
    }

    private companion object {
        const val MAX_MESSAGE = 2000
        const val MAX_APP_VERSION = 40
        const val MAX_DEVICE_MODEL = 100
        const val MAX_FLAVOR = 16
        const val MAX_LOCALE = 35
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface FirestoreFeedbackModule {
    @Binds
    fun feedbackSender(impl: FirestoreFeedbackSender): FeedbackSender
}
