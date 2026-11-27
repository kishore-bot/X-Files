package com.xfiles.android

import android.app.Application
import android.content.ComponentCallbacks2
import com.xfiles.android.shortcuts.keepRecentShortcutsCurrent
import com.xfiles.android.widget.keepWidgetCurrent
import com.xfiles.core.documents.MemoryPressure
import dagger.hilt.android.HiltAndroidApp

/** Dependencies are constructed by Hilt; repositories are @Singleton @Inject classes in :core:documents. */
@HiltAndroidApp
class XfilesApplication : Application() {
    override fun onCreate() {
        installCrashReporter(this) // First, so a crash during start-up is recorded too.
        super.onCreate() // Hilt injects here.
        keepWidgetCurrent() // Background collector; does nothing unless a widget is placed.
        keepRecentShortcutsCurrent() // Long-press shortcuts for the newest recent files, off the main thread.
    }

    /** Caches give memory back once the UI is hidden or the system runs low; they refill on demand. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            MemoryPressure.trim()
        }
    }
}
