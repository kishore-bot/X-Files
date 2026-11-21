package com.xfiles.android.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xfiles.android.MainActivity

/**
 * What the home-screen widget asks of the app. Buttons send an explicit intent to [MainActivity] with
 * [EXTRA] set to one of the action names; a recent file is an ACTION_VIEW on its content URI, which the
 * activity already opens in the reader.
 */
object WidgetActions {
    const val EXTRA = "xfiles.action"
    const val SCAN = "scan"
    const val FILY = "fily"
    const val OPEN = "open"
    /** Opens the Tools screen (app shortcut). */
    const val TOOLS = "tools"
    /** Opens a new text note prefilled with [EXTRA_NOTE_TITLE] and [EXTRA_NOTE_TEXT] (Android AppFunctions). */
    const val NOTE = "note"
    const val EXTRA_NOTE_TITLE = "xfiles.note.title"
    const val EXTRA_NOTE_TEXT = "xfiles.note.text"

    fun button(context: Context, action: String): Intent =
        Intent(context, MainActivity::class.java).putExtra(EXTRA, action)

    fun file(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri)
            .setComponent(ComponentName(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun note(context: Context, title: String, text: String): Intent =
        button(context, NOTE)
            .putExtra(EXTRA_NOTE_TITLE, title)
            .putExtra(EXTRA_NOTE_TEXT, text)
}
