package com.xfiles.android.feedback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.xfiles.feedback.FeedbackDestination
import com.xfiles.feedback.FeedbackReport
import com.xfiles.feedback.FeedbackResult
import com.xfiles.feedback.FeedbackSender
import com.xfiles.feedback.plainText
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Builds without Firebase (dev, and prod without the firebase switch). No network code: the report goes to the
 * system share sheet, and the user picks the app that receives it.
 */
internal class ShareFeedbackSender @Inject constructor(
    @ApplicationContext private val context: Context,
) : FeedbackSender {
    override val destination = FeedbackDestination.Share

    override suspend fun send(report: FeedbackReport): FeedbackResult {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Xfiles feedback")
            putExtra(Intent.EXTRA_TEXT, report.plainText())
        }
        val chooser = Intent.createChooser(share, "Send feedback").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            FeedbackResult.Shared
        } catch (_: ActivityNotFoundException) {
            FeedbackResult.Failed("No app is available to share with on this phone.")
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface ShareFeedbackModule {
    @Binds
    fun feedbackSender(impl: ShareFeedbackSender): FeedbackSender
}
