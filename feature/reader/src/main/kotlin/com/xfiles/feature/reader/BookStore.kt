package com.xfiles.feature.reader

import android.content.Context
import com.xfiles.documents.book.BookAnnotations
import com.xfiles.documents.book.ReadingSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reader's files: one JSON file of annotations per book, and the reading settings. Reads and
 * writes run on one IO lane, so files are never touched by two threads at once. A write goes to a
 * temporary file that is then renamed over the old one, so a crash leaves the previous version whole.
 */
@Singleton
class BookStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "books")
    private val lane = Dispatchers.IO.limitedParallelism(1)

    suspend fun loadAnnotations(key: String): BookAnnotations =
        BookAnnotations.decodeOrEmpty(read("$key.json"))

    suspend fun saveAnnotations(key: String, annotations: BookAnnotations) {
        write("$key.json", BookAnnotations.encode(annotations))
    }

    suspend fun loadSettings(): ReadingSettings = ReadingSettings.decodeOrDefault(read(SETTINGS_FILE))

    suspend fun saveSettings(settings: ReadingSettings) {
        write(SETTINGS_FILE, ReadingSettings.encode(settings))
    }

    suspend fun loadReadingMode(): BookReadingMode = BookReadingMode.fromStored(read(MODE_FILE))

    suspend fun saveReadingMode(mode: BookReadingMode) {
        write(MODE_FILE, mode.name)
    }

    private suspend fun read(name: String): String? = withContext(lane) {
        val file = File(dir, name)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return@withContext null
        runCatching { file.readText() }.getOrNull()
    }

    private suspend fun write(name: String, text: String) = withContext(lane) {
        runCatching {
            dir.mkdirs()
            val target = File(dir, name)
            val temp = File(dir, "$name.tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
        Unit
    }

    private companion object {
        const val SETTINGS_FILE = "reading-settings.json"
        const val MODE_FILE = "reading-mode.txt"
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }
}
