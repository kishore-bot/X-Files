package com.xfiles.feature.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * "Original view": the file as its author made it (white page, no glass tint). It is chosen per file type
 * (extension), so `.docx` and `.md` can differ, and it is remembered across sessions.
 */
internal val LocalOriginalView = compositionLocalOf { false }

/** Page colours used by text-based viewers when the original view is on. */
internal val ORIGINAL_PALETTE = ReadingPalette(
    page = Color.White,
    text = Color(0xFF161616),
    secondary = Color(0xFF5A5A5A),
    syntaxDark = false,
)

private const val PREFS_NAME = "reader_original_view"

/** Preference key for [name]: its lower-case extension, or "none" for an extension-less file. */
internal fun originalViewKey(name: String): String =
    name.substringAfterLast('.', "").lowercase().ifEmpty { "none" }

private fun prefs(context: Context): SharedPreferences? =
    try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

/** The saved choice for [key]; false (themed view) when nothing is saved or storage is unavailable. */
internal fun readOriginalView(context: Context, key: String): Boolean =
    try {
        prefs(context)?.getBoolean(key, false) ?: false
    } catch (_: Exception) {
        false
    }

internal fun writeOriginalView(context: Context, key: String, original: Boolean) {
    try {
        prefs(context)?.edit()?.putBoolean(key, original)?.apply()
    } catch (_: Exception) {
        // Without storage the choice still applies for this session.
    }
}
