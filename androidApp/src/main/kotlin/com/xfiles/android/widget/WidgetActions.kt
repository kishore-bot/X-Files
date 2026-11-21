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

    fun button(context: Context, action: String): Intent =
        Intent(context, MainActivity::class.java).putExtra(EXTRA, action)

    fun file(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri)
            .setComponent(ComponentName(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
