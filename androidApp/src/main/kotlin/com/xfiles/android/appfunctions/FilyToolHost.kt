package com.xfiles.android.appfunctions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appfunctions.AppFunctionAppUnknownException
import com.xfiles.android.widget.WidgetActions
import com.xfiles.fily.AssistantAccess
import com.xfiles.fily.FilyDestination
import com.xfiles.fily.FilyNavigator
import com.xfiles.fily.FilyHandoff
import com.xfiles.fily.FilyTool
import com.xfiles.fily.ReaderControl
import com.xfiles.fily.ToolExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The AppFunctions' back end. It runs the same [ToolExecutor] the Fily overlay uses, so the access switches,
 * content gate, result shapes and hand-off rules are one implementation. Files come from the shared
 * [FilyDocumentsSource] (recents and linked folders), refreshed before each call.
 *
 * Screen hand-offs (open, scan, new note) are not started from the service. The navigator only records the
 * destination, and [handoff] returns a [PendingIntent] for it, which the agent launches; the app therefore never
 * starts an activity from a background service, and the launch uses the platform's PendingIntent rules.
 *
 * One executor lives for the process so the "doc_N" ids a search returns still resolve on the next call. Calls
 * are serialized, because the executor keeps its alias table unsynchronized.
 */
@Singleton
class FilyToolHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: FilyDocumentsSource,
    access: AssistantAccess,
    reader: ReaderControl,
) {
    private val navigator = RecordingNavigator()
    private val executor = ToolExecutor(access, source.view(), reader, navigator)
    private val lock = Mutex()

    /** Runs a Fily tool that returns data (search, info, excerpt) and yields its compact JSON reply. */
    suspend fun run(tool: String, arguments: JsonObject): String = lock.withLock {
        withContext(Dispatchers.IO) {
            source.refresh()
            executor.execute(tool, arguments)
        }
    }

    /**
     * Runs a Fily tool that opens a screen and returns the [PendingIntent] for it. Throws the library's
     * exception when Fily refuses (access off, unknown file, or the screen is not available).
     */
    suspend fun handoff(tool: String, arguments: JsonObject): PendingIntent = lock.withLock {
        withContext(Dispatchers.IO) {
            source.refresh()
            navigator.destination = null
            val reply = executor.execute(tool, arguments)
            val intent = navigator.destination?.let(::intentFor)
                ?: throw AppFunctionAppUnknownException(reasonOf(reply))
            PendingIntent.getActivity(
                context,
                (System.nanoTime() and 0x7fffffff).toInt(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
        }
    }

    private fun intentFor(destination: FilyDestination): Intent? = when (destination) {
        is FilyDestination.Open -> WidgetActions.file(context, Uri.parse(destination.documentId))
        is FilyDestination.Tool -> when (destination.tool) {
            FilyTool.ScanToPdf -> WidgetActions.button(context, WidgetActions.SCAN)
            FilyTool.NewText -> WidgetActions.note(context, destination.draftTitle.orEmpty(), destination.draftText.orEmpty())
            else -> null
        }
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The failure reason Fily reported (access_off, content_restricted, not_available, ...). */
    private fun reasonOf(reply: String): String =
        runCatching { (Json.parseToJsonElement(reply) as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull }
            .getOrNull() ?: "not_available"
}

/** Records the destination Fily asks for; the host turns it into a PendingIntent after the call. */
private class RecordingNavigator : FilyNavigator {
    @Volatile
    var destination: FilyDestination? = null

    override suspend fun go(destination: FilyDestination): FilyHandoff {
        this.destination = destination
        return FilyHandoff.Accepted
    }
}
