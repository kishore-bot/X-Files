package com.xfiles.documentengine

import com.xfiles.engine.ffi.NativeIndexEntry
import com.xfiles.engine.ffi.NativeSearchIndex

data class IndexedDocument(val key: String, val size: Long, val modified: Long)
data class IndexedMatch(val key: String, val snippet: String, val matches: List<IntRange>)

class DocumentTextIndex(path: String) : AutoCloseable {
    private val index = NativeSearchIndex(path)

    fun retain(entries: List<IndexedDocument>) {
        index.retain(entries.map(IndexedDocument::key))
    }

    fun stale(entries: List<IndexedDocument>): List<Int> =
        index.stale(entries.map { NativeIndexEntry(it.key, it.size.coerceAtLeast(0).toULong(), it.modified) }).map { it.toInt() }

    fun hasRoom(): Boolean = index.hasRoom()

    fun add(entry: IndexedDocument, content: String): Boolean =
        index.add(entry.key, entry.size.coerceAtLeast(0).toULong(), entry.modified, content, emptyList())

    fun query(query: String): List<IndexedMatch> = index.query(query, 50u).map { hit ->
        IndexedMatch(hit.key, hit.snippet, hit.ranges.map { it.start.toInt() until it.end.toInt() })
    }

    fun save() = index.save()
    fun clear() = index.clear()
    override fun close() = index.close()
}
