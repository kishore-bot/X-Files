package com.xfiles.android

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.xfiles.crash.CrashReport
import com.xfiles.feedback.FeedbackResult
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

fun createCrashReporting(context: Context): CrashReportingSetup = FirebaseCrashReporting(context.applicationContext)

private class FirebaseCrashReporting(app: Context) : CrashReportingSetup {
    private val preferences = app.getSharedPreferences("firestore_crash_consent", Context.MODE_PRIVATE)
    private val allowed = MutableStateFlow(preferences.getLong(CONSENT_SINCE, 0L) > 0L)
    private val firestore = FirestoreReports()
    private val reportNamespace by lazy {
        preferences.getString(REPORT_NAMESPACE, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(REPORT_NAMESPACE, it).apply()
        }
    }
    override val available = true
    override val enabled = allowed.asStateFlow()

    override fun shouldSendAutomatically(report: CrashReport): Boolean {
        val allowedSince = preferences.getLong(CONSENT_SINCE, 0L)
        return allowedSince > 0L && report.savedAtMillis >= allowedSince
    }

    @Suppress("UseKtx")
    override suspend fun setEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            val since = if (enabled) System.currentTimeMillis() else 0L
            if (!preferences.edit().putLong(CONSENT_SINCE, since).commit()) {
                throw IOException("Could not save crash reporting preference")
            }
            allowed.value = enabled
        }
    }

    override suspend fun send(report: CrashReport): FeedbackResult {
        val reportId = MessageDigest.getInstance("SHA-256")
            .digest("$reportNamespace:${report.id}".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return firestore.sendCrash(
            reportId = reportId,
            fields = mapOf(
                "report" to report.text,
                "appVersion" to report.versionName.take(40),
                "platform" to "android",
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        )
    }

    private companion object {
        const val CONSENT_SINCE = "allowed_since"
        const val REPORT_NAMESPACE = "report_namespace"
    }
}
