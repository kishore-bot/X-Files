package com.xfiles.documentengine

import com.xfiles.engine.ffi.NativePassageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text as the passage index reads it: characters, and the character offset where each PDF page starts. */
data class PassageSource(val text: String, val pageStarts: List<Int>)

/** A passage of a document, and the 1-based PDF page it starts on. */
data class PassageHit(val text: String, val page: Int?)

/** The passages answering a question. [documentBytes] is the size of the indexed text, so a caller can tell that all of it fits. */
data class PassageHits(val passages: List<PassageHit>, val omitted: Boolean, val documentBytes: Long)

/**
 * Passage retrieval for Fily: one BM25 index per document, in a bounded LRU. An index is rebuilt when the file's
 * modified time or size changes, so an edited file is never answered from its old text. Building an index is CPU
 * work and runs on the default dispatcher; a cached question costs one lookup and one ranking.
 */
/** One per process: provided as a singleton by the app's Hilt graph (PassagesModule). */
class DocumentPassages {
    private val cache = NativePassageCache(MAX_DOCUMENTS, MAX_BYTES)

    /**
     * The passages of [key] that answer [question] within [budget] characters. [load] reads the text when the
     * document is not indexed yet (it does its own IO). Null when the file has no modified time or size to validate
     * the index with, so the caller falls back to plain excerpts.
     */
    suspend fun select(
        key: String,
        modifiedAt: Long?,
        sizeBytes: Long?,
        question: String,
        budget: Int,
        load: suspend () -> PassageSource,
    ): PassageHits? {
        val mtime = modifiedAt ?: return null
        val size = sizeBytes?.toULong() ?: return null
        return withContext(Dispatchers.Default) {
            val limit = budget.toUInt()
            val hit = cache.select(key, mtime, size, question, limit) ?: run {
                val source = load()
                cache.index(key, mtime, size, source.text, source.pageStarts.map { it.toUInt() })
                cache.select(key, mtime, size, question, limit)
            } ?: return@withContext null
            PassageHits(
                passages = hit.passages.map { PassageHit(it.text, it.page?.toInt()) },
                omitted = hit.omitted,
                documentBytes = hit.documentBytes.toLong(),
            )
        }
    }

    private companion object {
        val MAX_DOCUMENTS: UInt = 8u
        val MAX_BYTES: ULong = 64uL * 1_048_576uL
    }
}
