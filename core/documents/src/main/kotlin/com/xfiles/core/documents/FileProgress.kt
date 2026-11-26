package com.xfiles.core.documents

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local viewer state. Keys describe a viewer and never contain passwords or document text. */
@Singleton
class FileProgress @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("file_progress", Context.MODE_PRIVATE)
    private val image = MutableStateFlow(preferences.getString("last_image", null))
    val lastImage = image.asStateFlow()

    fun read(file: String, field: String): String? = preferences.getString(key(file, field), null)

    fun write(file: String, field: String, value: String) {
        val key = key(file, field)
        if (preferences.getString(key, null) != value) preferences.edit().putString(key, value).apply()
    }

    fun viewImage(uri: String) {
        if (image.value == uri) return
        preferences.edit().putString("last_image", uri).apply()
        image.value = uri
    }

    private fun key(file: String, field: String): String {
        val id = identity(file)
        return "${id.length}:$id:$field"
    }

    companion object {
        /** The same SAF document can be addressed directly or through overlapping tree grants. */
        fun identity(file: String): String {
            val uri = Uri.parse(file)
            if (uri.scheme != "content" || uri.authority == null) return file
            return try {
                "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
            } catch (_: IllegalArgumentException) {
                file
            }
        }
    }
}
