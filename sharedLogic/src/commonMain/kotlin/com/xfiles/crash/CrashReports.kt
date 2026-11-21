package com.xfiles.crash

/**
 * Crash reports saved on this device (see [CrashReportFormatter]). Nothing is ever sent by the app:
 * [share] hands the text to an app the user picks. Calls read or delete small files, so callers keep
 * them off the main thread.
 */
interface CrashReports {
    /** The newest report not yet sent or dismissed, or `null`. */
    fun pending(): CrashReport?

    /** Deletes the report [id] and every older one. */
    fun dismiss(id: String)

    /** Opens the share sheet with the report as text; `false` if nothing can take it. */
    fun share(report: CrashReport): Boolean
}
