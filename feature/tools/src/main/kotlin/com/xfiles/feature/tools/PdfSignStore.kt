package com.xfiles.feature.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xfiles.documents.MAX_SAVED_SIGNATURES
import com.xfiles.documents.PdfSavedSignature
import com.xfiles.documents.decodePdfSavedSignatures
import com.xfiles.documents.encodePdfSavedSignatures
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Up to [MAX_SAVED_SIGNATURES] drawn signatures kept on this device only, in
 * private preferences. Reads never fail the screen: a damaged value is an empty list.
 */
@Singleton
class PdfSignStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<PdfSavedSignature> = try {
        decodePdfSavedSignatures(preferences.getString(KEY, null).orEmpty())
    } catch (_: RuntimeException) {
        emptyList()
    }

    fun store(signatures: List<PdfSavedSignature>) {
        try {
            preferences.edit().putString(KEY, encodePdfSavedSignatures(signatures.take(MAX_SAVED_SIGNATURES))).apply()
        } catch (_: RuntimeException) {
            // Keeping the signature in memory for this session is the fallback.
        }
    }

    private companion object {
        const val PREFERENCES = "pdf_signatures"
        const val KEY = "saved"
    }
}
