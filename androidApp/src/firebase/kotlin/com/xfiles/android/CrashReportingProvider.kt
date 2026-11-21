package com.xfiles.android

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

fun createCrashReporting(context: Context): CrashReportingSetup = FirebaseCrashReporting(context.applicationContext)

private class FirebaseCrashReporting(private val app: Context) : CrashReportingSetup {
    private val preferences = app.getSharedPreferences("cloud_crash_consent", Context.MODE_PRIVATE)
    private val allowed = MutableStateFlow(preferences.getBoolean("allowed", false))
    override val available = true
    override val enabled = allowed.asStateFlow()
    override var active = false
        private set
    private var sdk: FirebaseCrashlytics? = null

    override fun start(recorder: AndroidCrashRecorder) {
        if (!allowed.value) return // No Firebase initialization or client traffic without prior consent.
        try {
            checkNotNull(FirebaseApp.initializeApp(app))
            sdk = FirebaseCrashlytics.getInstance().also { it.setCrashlyticsCollectionEnabled(true) }
            active = true
        } catch (_: RuntimeException) {
            active = false // Local reports and the rest of the app remain available.
        } finally {
            recorder.captureCloudHandler { allowed.value && active }
        }
    }

    // KTX edit returns Unit; consent must check the synchronous commit result before acknowledging it.
    @Suppress("UseKtx")
    override suspend fun setEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            if (!preferences.edit().putBoolean("allowed", enabled).commit()) throw IOException("Could not save crash reporting preference")
        }
        allowed.value = enabled
        if (!enabled) {
            sdk?.setCrashlyticsCollectionEnabled(false)
            sdk?.deleteUnsentReports()
        }
    }
}
