package com.xfiles.feature.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.compositionLocalOf

private const val PREFS_NAME = "reader_page_style"
private const val TEXT_PREFS_NAME = "reader_text_size"

/** The text size of the code and plain-text viewers, as a multiple of the default; 1 is 100 percent. */
val LocalTextScale = compositionLocalOf { 1f }
internal const val TEXT_SCALE_MIN = 0.7f
internal const val TEXT_SCALE_MAX = 2.0f
internal const val TEXT_SCALE_STEP = 0.1f

/** Preference key for [name]: its lower-case extension, or "none" for an extension-less file. */
internal fun pageStyleKey(name: String): String =
    name.substringAfterLast('.', "").lowercase().ifEmpty { "none" }

private fun prefs(context: Context, name: String = PREFS_NAME): SharedPreferences? =
    try {
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

/** The saved page style for [key]; [PageStyle.Standard] when nothing is saved or storage is unavailable. */
internal fun readPageStyle(context: Context, key: String): PageStyle {
    val saved = try {
        prefs(context)?.getString(key, null)
    } catch (_: Exception) {
        null
    }
    return PageStyle.entries.firstOrNull { it.name == saved } ?: PageStyle.Standard
}

internal fun writePageStyle(context: Context, key: String, style: PageStyle) {
    try {
        prefs(context)?.edit()?.putString(key, style.name)?.apply()
    } catch (_: Exception) {
        // Without storage the choice still applies for this session.
    }
}

/** The saved text scale for a viewer [kind] ("code" or "text"); 1 when nothing is saved. */
internal fun readTextScale(context: Context, kind: String): Float {
    val saved = try {
        prefs(context, TEXT_PREFS_NAME)?.getFloat(kind, 1f) ?: 1f
    } catch (_: Exception) {
        1f
    }
    return saved.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
}

internal fun writeTextScale(context: Context, kind: String, scale: Float) {
    try {
        prefs(context, TEXT_PREFS_NAME)?.edit()?.putFloat(kind, scale.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX))?.apply()
    } catch (_: Exception) {
        // Without storage the size still applies for this session.
    }
}
