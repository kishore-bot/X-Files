package com.xfiles.android

import com.xfiles.crash.CloudCrashReporting
import com.xfiles.crash.CrashReport
import com.xfiles.feedback.FeedbackResult

interface CrashReportingSetup : CloudCrashReporting {
    fun shouldSendAutomatically(report: CrashReport): Boolean

    suspend fun send(report: CrashReport): FeedbackResult
}
