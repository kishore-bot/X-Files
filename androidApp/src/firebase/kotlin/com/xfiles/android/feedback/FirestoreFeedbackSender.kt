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
        val document = buildMap<String, Any> {
            put("category", report.category.wire)
            put("message", report.message)
            put("createdAt", FieldValue.serverTimestamp())
            report.environment?.let { env ->
                put("appVersion", env.appVersion)
                put("versionCode", env.versionCode)
                put("deviceModel", env.deviceModel)
                put("androidSdk", env.androidSdk)
                put("flavor", env.flavor)
                put("locale", env.locale)
            }
        }
        return firestore.sendFeedback(document)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface FirestoreFeedbackModule {
    @Binds
    fun feedbackSender(impl: FirestoreFeedbackSender): FeedbackSender
}
