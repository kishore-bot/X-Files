package com.xfiles.documentengine

import com.xfiles.engine.ffi.NativeIndexEntry
import com.xfiles.engine.ffi.NativeSearchIndex

data class IndexedDocument(val key: String, val size: Long, val modified: Long)
data class IndexedMatch(val key: String, val snippet: String, val matches: List<IntRange>)

class DocumentTextIndex private constructor(private val index: NativeSearchIndex) : AutoCloseable {
    constructor(path: String) : this(NativeSearchIndex(path))

    companion object {
        fun committed(path: String) = DocumentTextIndex(NativeSearchIndex.committed(path))
    }

    fun retain(entries: List<IndexedDocument>): Int = index.retain(entries.map(IndexedDocument::key)).toInt()

    fun stale(entries: List<IndexedDocument>): List<Int> =
        index.stale(entries.map { NativeIndexEntry(it.key, it.size.coerceAtLeast(0).toULong(), it.modified) }).map { it.toInt() }

    fun hasRoom(): Boolean = index.hasRoom()

    fun add(entry: IndexedDocument, content: String): Boolean =
        index.add(entry.key, entry.size.coerceAtLeast(0).toULong(), entry.modified, content, emptyList())

    fun query(query: String, limit: Int = 50): List<IndexedMatch> = index.query(query, limit.coerceIn(0, 50).toUInt()).map { hit ->
        IndexedMatch(hit.key, hit.snippet, hit.ranges.map { it.start.toInt() until it.end.toInt() })
    }

    fun save() = index.save()
    fun clear() = index.clear()
    override fun close() = index.close()
}
