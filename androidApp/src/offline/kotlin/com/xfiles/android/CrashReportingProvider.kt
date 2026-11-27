package com.xfiles.android

import android.content.Context
import com.xfiles.crash.CloudCrashReporting
import com.xfiles.crash.CrashReport
import com.xfiles.crash.NoCloudCrashReporting
import com.xfiles.feedback.FeedbackResult

@Suppress("UNUSED_PARAMETER")
fun createCrashReporting(context: Context): CrashReportingSetup = object : CrashReportingSetup,
    CloudCrashReporting by NoCloudCrashReporting {
    override fun shouldSendAutomatically(report: CrashReport): Boolean = false

    override suspend fun send(report: CrashReport): FeedbackResult =
        FeedbackResult.Failed("Crash reporting is unavailable in this build.")
}
