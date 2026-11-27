package com.xfiles.android.feedback

import com.xfiles.feedback.FeedbackReport
import com.xfiles.feedback.FeedbackResult
import com.xfiles.feedback.FeedbackSender
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

internal class OfflineFeedbackSender @Inject constructor() : FeedbackSender {
    override val available = false

    override suspend fun send(report: FeedbackReport): FeedbackResult =
        FeedbackResult.Failed("Feedback is unavailable in this build.")
}

@Module
@InstallIn(SingletonComponent::class)
internal interface OfflineFeedbackModule {
    @Binds
    fun feedbackSender(impl: OfflineFeedbackSender): FeedbackSender
}
