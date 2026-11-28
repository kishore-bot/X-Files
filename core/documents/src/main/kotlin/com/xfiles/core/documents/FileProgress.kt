package com.xfiles.core.documents

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xfiles.documents.library.LibraryCategory
import com.xfiles.documents.library.libraryCategories
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackProgress(val positionMs: Long, val durationMs: Long?)

/** Local viewer state. Keys describe a viewer and never contain passwords or document text. */
@Singleton
class FileProgress @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("file_progress", Context.MODE_PRIVATE)
    private val viewed = MutableStateFlow(
        buildMap {
            for (category in LibraryCategory.entries) {
                val previous = preferences.getString(historyKey(category), null)
                    ?: if (category == LibraryCategory.Images) preferences.getString("last_image", null) else null
                if (previous != null) put(category, previous)
            }
        },
    )
    val lastViewed = viewed.asStateFlow()
    private val mutablePlaybackRevision = MutableStateFlow(0L)
    val playbackRevision = mutablePlaybackRevision.asStateFlow()

    fun read(file: String, field: String): String? = preferences.getString(key(file, field), null)

    fun write(file: String, field: String, value: String) {
        val key = key(file, field)
        if (preferences.getString(key, null) != value) preferences.edit().putString(key, value).apply()
    }

    fun markViewed(file: FileEntry) {
        val uri = file.uri.toString()
        val categories = libraryCategories(file.name) + LibraryCategory.All
        val changed = categories.filter { viewed.value[it] != uri }
        if (changed.isEmpty()) return
        val edit = preferences.edit()
        for (category in changed) edit.putString(historyKey(category), uri)
        edit.apply()
        viewed.update { previous -> previous + changed.associateWith { uri } }
    }

    fun playback(file: String): PlaybackProgress? {
        val position = read(file, "media-at")?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val duration = read(file, "media-duration")?.toLongOrNull()?.takeIf { it > 0L }
        return PlaybackProgress(duration?.let { position.coerceAtMost(it) } ?: position, duration)
    }

    fun savePlayback(file: String, positionMs: Long, durationMs: Long) {
        val positionKey = key(file, "media-at")
        val durationKey = key(file, "media-duration")
        val position = positionMs.coerceAtLeast(0L).toString()
        val duration = durationMs.takeIf { it > 0L }?.toString()
        if (preferences.getString(positionKey, null) == position &&
            (duration == null || preferences.getString(durationKey, null) == duration)
        ) return
        preferences.edit().putString(positionKey, position).apply {
            if (duration != null) putString(durationKey, duration)
        }.apply()
        mutablePlaybackRevision.update { it + 1 }
    }

    private fun historyKey(category: LibraryCategory) = "last_viewed_${category.name}"

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
