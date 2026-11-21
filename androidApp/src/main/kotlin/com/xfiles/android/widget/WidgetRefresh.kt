package com.xfiles.android.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.xfiles.core.documents.DocumentRepository
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import androidx.glance.appwidget.updateAll
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Lets the widget (a plain receiver, not a Hilt entry point) reach the singleton recents store. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun documents(): DocumentRepository
}

private const val RECENTS_SETTLE_MS = 400L

/**
 * Refreshes the widget whenever recents change. It runs on its own IO coroutine, so nothing here is on
 * the start-up path, and it does nothing unless a widget is placed.
 */
internal fun Application.keepWidgetCurrent() {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val documents = EntryPointAccessors.fromApplication(this@keepWidgetCurrent, WidgetEntryPoint::class.java).documents()
        documents.recents.debounce(RECENTS_SETTLE_MS).collect {
            val component = ComponentName(this@keepWidgetCurrent, XfilesWidgetReceiver::class.java)
            val placed = AppWidgetManager.getInstance(this@keepWidgetCurrent).getAppWidgetIds(component)
            if (placed.isNotEmpty()) XfilesWidget().updateAll(this@keepWidgetCurrent)
        }
    }
}
