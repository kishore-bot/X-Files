package com.xfiles.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xfiles.documents.GlanceItem
import com.xfiles.documents.GlanceKind
import java.util.Calendar
import java.util.TimeZone

/**
 * The things to act on in a document, grouped by kind as chips. Each chip does the obvious thing: a date adds a
 * calendar event, an amount is copied, a phone number dials, an address opens a new message, a link opens, and a
 * reference is searched for in the document (a PDF) or copied for pasting into its find bar (other files).
 */
@Composable
internal fun GlanceItemGroups(
    items: List<GlanceItem>,
    documentName: String,
    onReference: (String) -> Unit,
) {
    val context = LocalContext.current
    val groups = items.groupBy { it.kind }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlanceKind.entries.forEach { kind ->
            val group = groups[kind].orEmpty()
            if (group.isEmpty()) return@forEach
            Text(kind.heading(), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                group.distinctBy { it.kind to it.value }.forEach { item ->
                    val (verb, action) = item.actionFor(context, documentName, onReference)
                    AssistChip(
                        onClick = action,
                        label = { Text(item.text.take(MAX_CHIP_CHARS)) },
                        modifier = Modifier.semantics { contentDescription = "$verb: ${item.text}" },
                    )
                }
            }
        }
    }
}

private const val MAX_CHIP_CHARS = 48

private fun GlanceKind.heading(): String = when (this) {
    GlanceKind.Date -> "Dates"
    GlanceKind.Amount -> "Amounts"
    GlanceKind.Phone -> "Phone numbers"
    GlanceKind.Email -> "Email addresses"
    GlanceKind.Link -> "Links"
    GlanceKind.Reference -> "References"
}

/** The chip's spoken verb and its action, for [item]. */
private fun GlanceItem.actionFor(
    context: Context,
    documentName: String,
    onReference: (String) -> Unit,
): Pair<String, () -> Unit> = when (kind) {
    GlanceKind.Date -> "Add to calendar" to { addToCalendar(context, value, label ?: documentName) }
    GlanceKind.Amount -> "Copy amount" to { copyToClipboard(context, text) }
    GlanceKind.Phone -> "Call" to { launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$value"))) }
    GlanceKind.Email -> "Email" to { launch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$value"))) }
    GlanceKind.Link -> "Open link" to { launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
    GlanceKind.Reference -> "Find in document" to { onReference(value) }
}

private fun addToCalendar(context: Context, isoDate: String, title: String) {
    // All-day events start at UTC midnight of the date, as the calendar provider expects for all-day entries.
    val parts = isoDate.split('-')
    val start = if (parts.size == 3) {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(parts[0].toIntOrNull() ?: 1970, (parts[1].toIntOrNull() ?: 1) - 1, parts[2].toIntOrNull() ?: 1)
        }.timeInMillis
    } else {
        null
    }
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
    start?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
    launch(context, intent)
}

private fun launch(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this.", Toast.LENGTH_SHORT).show()
    }
}

/** Puts [text] on the clipboard with a short confirmation. */
internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Glance", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
