package com.xfiles.android.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xfiles.android.MainActivity
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What the home-screen widget asks of the app. Buttons send an explicit intent to [MainActivity] with
 * [EXTRA] set to one of the action names; a recent file is an ACTION_VIEW on its content URI, which the
 * activity already opens in the reader.
 *
 * MainActivity is exported, so any app can send these extras. The plain screen actions only open a screen, like
 * launching the app. [NOTE] carries caller text, so it is honoured only with [EXTRA_TOKEN], a per-install secret
 * that only intents built in this process (the AppFunctions hand-off) carry.
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
    private const val EXTRA_TOKEN = "xfiles.token"

    private const val PREFS = "xfiles_internal_launch"
    private const val TOKEN_KEY = "token"

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
            .putExtra(EXTRA_TOKEN, token(context))

    /** True when [intent] was built by [note] in this app, not sent by another app. */
    fun isInternal(context: Context, intent: Intent): Boolean {
        val presented = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), token(context).toByteArray())
    }

    /** The per-install secret, created on first use. Backups are disabled, so it never leaves the device. */
    @Synchronized
    private fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(TOKEN_KEY, null)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val created = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(TOKEN_KEY, created).commit()
        return created
    }
}
