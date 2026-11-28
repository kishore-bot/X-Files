package com.xfiles.core.documents

import android.net.Uri

/**
 * One-shot hand-off from a file's menu to the Compress or Extract tool. The library stages a file here, then opens
 * the tool; the tool's ViewModel takes it on start. Nothing is persisted.
 */
object ArchiveHandoff {
    private var sources: List<Uri> = emptyList()
    private var archive: Pair<Uri, String>? = null

    /** Stage files to compress when the Compress tool opens. */
    fun stageCompress(files: List<Uri>) {
        sources = files
    }

    /** Stage a zip to extract when the Extract tool opens. */
    fun stageExtract(uri: Uri, name: String) {
        archive = uri to name
    }

    fun takeSources(): List<Uri> = sources.also { sources = emptyList() }

    fun takeArchive(): Pair<Uri, String>? = archive.also { archive = null }
}
