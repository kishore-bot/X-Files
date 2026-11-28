package com.xfiles.core.documents

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Share original: hands the user's own files to the app they pick in the system share sheet. The
 * receiver gets a temporary read grant for exactly these URIs, never write access, and nothing is copied.
 */
@Singleton
class DocumentSharing @Inject constructor(@ApplicationContext context: Context) {
    private val app = context.applicationContext

    /** False when no app can take the files. */
    fun share(files: List<Pair<Uri, String>>): Boolean {
        if (files.isEmpty()) return false
        val resolver = app.contentResolver
        val types = files.map { (uri, _) -> runCatching { resolver.getType(uri) }.getOrNull() ?: "*/*" }.distinct()
        val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val clip = ClipData.newRawUri(files.first().second, files.first().first).apply {
            files.drop(1).forEach { (uri, _) -> addItem(ClipData.Item(uri)) }
        }
        val send = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, files.single().first)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.first }))
        }.apply {
            setType(type)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val title = if (files.size == 1) "Share ${files.single().second}" else "Share ${files.size} files"
        return try {
            app.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun share(file: FileEntry): Boolean = share(listOf(file.uri to file.name))
}
