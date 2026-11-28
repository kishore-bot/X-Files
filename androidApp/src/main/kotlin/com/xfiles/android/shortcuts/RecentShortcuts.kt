package com.xfiles.android.shortcuts

import android.app.Application
import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.xfiles.android.R
import com.xfiles.android.widget.WidgetActions
import com.xfiles.android.widget.WidgetEntryPoint
import com.xfiles.core.documents.FileEntry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The launcher shows only a few shortcuts per app: three static ones (Scan, Fily, Open) and two recent files,
 * so none is trimmed.
 */
internal const val RECENT_SHORTCUT_COUNT = 2
private const val RECENTS_SETTLE_MS = 500L

/**
 * Keeps the long-press shortcuts for the newest recent files current. Recents are collected off the main thread,
 * debounced so a burst of changes publishes once, and only the top [RECENT_SHORTCUT_COUNT] files are sent.
 */
internal fun Application.keepRecentShortcutsCurrent() {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val documents = EntryPointAccessors.fromApplication(this@keepRecentShortcutsCurrent, WidgetEntryPoint::class.java).documents()
        documents.recents
            .debounce(RECENTS_SETTLE_MS)
            .map { files -> files.take(RECENT_SHORTCUT_COUNT) }
            .distinctUntilChanged()
            .collect { files -> publish(this@keepRecentShortcutsCurrent, files) }
    }
}

/** Replaces the dynamic shortcuts; an empty list removes them. A refused update never crashes the app. */
internal fun publish(context: Context, files: List<FileEntry>) {
    val shortcuts = files.map { file ->
        ShortcutInfoCompat.Builder(context, "recent_" + file.uri.toString().hashCode().toString(16))
            .setShortLabel(file.name)
            .setLongLabel(file.name)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_file))
            .setIntent(WidgetActions.file(context, file.uri))
            .build()
    }
    runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
}
