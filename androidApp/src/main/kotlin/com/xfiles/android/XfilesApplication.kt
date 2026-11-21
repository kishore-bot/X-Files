package com.xfiles.android

import android.app.Application
import com.xfiles.android.shortcuts.keepRecentShortcutsCurrent
import com.xfiles.android.widget.keepWidgetCurrent
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Dependencies are constructed by Hilt; repositories are @Singleton @Inject classes in :core:documents. */
@HiltAndroidApp
class XfilesApplication : Application() {
    @Inject lateinit var cloudCrash: CrashReportingSetup

    override fun onCreate() {
        val recorder = installCrashReporter(this) // First, so a crash during start-up is recorded too.
        super.onCreate() // Hilt injects here.
        cloudCrash.start(recorder) // Optional cloud reporting; a no-op unless the firebase build and consent are both present.
        keepWidgetCurrent() // Background collector; does nothing unless a widget is placed.
        keepRecentShortcutsCurrent() // Long-press shortcuts for the newest recent files, off the main thread.
    }
}
