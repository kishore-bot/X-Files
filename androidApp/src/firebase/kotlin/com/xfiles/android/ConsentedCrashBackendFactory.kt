package com.xfiles.android

import android.content.Context
import com.google.android.datatransport.cct.CctBackendFactory
import com.google.android.datatransport.runtime.EventInternal
import com.google.android.datatransport.runtime.backends.BackendFactory
import com.google.android.datatransport.runtime.backends.BackendRequest
import com.google.android.datatransport.runtime.backends.BackendResponse
import com.google.android.datatransport.runtime.backends.CreationContext
import com.google.android.datatransport.runtime.backends.TransportBackend

/** The shared transport must never become an upload route for OCR/other telemetry. */
class ConsentedCrashBackendFactory : BackendFactory {
    override fun create(context: CreationContext): TransportBackend {
        val preferences = context.applicationContext.getSharedPreferences("cloud_crash_consent", Context.MODE_PRIVATE)
        val delegate = CctBackendFactory().create(context)
        return object : TransportBackend {
            override fun decorate(event: EventInternal): EventInternal =
                if (event.transportName == "FIREBASE_CRASHLYTICS_REPORT") delegate.decorate(event) else event

            override fun send(request: BackendRequest): BackendResponse {
                if (!preferences.getBoolean("allowed", false)) return BackendResponse.ok(0, null)
                val reports = request.events.filter { it.transportName == "FIREBASE_CRASHLYTICS_REPORT" }
                if (reports.isEmpty()) return BackendResponse.ok(0, null)
                return delegate.send(BackendRequest.builder().setEvents(reports).setExtras(request.extras).build())
            }
        }
    }
}
