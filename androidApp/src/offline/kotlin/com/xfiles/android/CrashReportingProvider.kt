package com.xfiles.android

import android.content.Context
import com.xfiles.crash.CloudCrashReporting
import com.xfiles.crash.NoCloudCrashReporting

/** Default build: no SDK, no network code, no consent switch. */
fun createCrashReporting(context: Context): CrashReportingSetup = object : CrashReportingSetup,
    CloudCrashReporting by NoCloudCrashReporting {
    override fun start(recorder: AndroidCrashRecorder) {}
}
