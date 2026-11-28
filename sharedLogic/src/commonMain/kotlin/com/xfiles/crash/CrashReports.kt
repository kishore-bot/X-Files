package com.xfiles.crash

/** Crash reports saved on this device. Callers keep disk access off the main thread. */
interface CrashReports {
    /** The newest report waiting for review, or `null`. */
    fun pending(): CrashReport?

    /** Deletes the report [id] and every older one. */
    fun dismiss(id: String)
}
