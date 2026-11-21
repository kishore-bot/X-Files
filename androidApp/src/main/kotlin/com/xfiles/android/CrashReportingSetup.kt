package com.xfiles.android

import com.xfiles.crash.CloudCrashReporting

/**
 * The optional cloud reporter. The firebase build (`-Pxfiles.firebaseCrashReports=true`) supplies the
 * Crashlytics implementation in `src/firebase`; the default build supplies a no-op in `src/offline`.
 */
interface CrashReportingSetup : CloudCrashReporting {
    /** Called once, after [installCrashReporter]; decides from the stored consent whether the SDK starts. */
    fun start(recorder: AndroidCrashRecorder)
}
