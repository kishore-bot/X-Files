package com.xfiles.tidy

/**
 * The algorithms behind Tidy up, implemented in Rust (`document-tidy`). Every call blocks on CPU or
 * I/O; callers run them on their IO lane, never the main thread.
 */
interface TidyEngine {
    /** Groups identical files. Only files sharing a size are opened, through [source]; known hashes are reused. */
    fun findIdentical(files: List<HashInput>, source: HashSource): IdenticalHashes

    /** One pass over a document's text: its SimHash and its collection. */
    fun analyzeText(name: String, text: String): TextFacts

    /** The collection a file belongs to by its name alone. */
    fun classifyName(name: String): CollectionMatch?

    /** dHash of a [width] x [height] luminance buffer; `null` if flat or too small. */
    fun imageFingerprint(width: Int, height: Int, gray: ByteArray): Long?

    /** Indices of fingerprints near each other; [images] picks the photo threshold. */
    fun groupSimilar(fingerprints: List<Long>, images: Boolean): List<List<Int>>

    fun sensitive(text: String): SensitiveFindings
}

data class HashInput(val size: Long, val partial: Long? = null, val full: Long? = null)

data class IdenticalHashes(val groups: List<List<Int>>, val partial: List<Long?>, val full: List<Long?>)

data class TextFacts(val fingerprint: Long?, val collection: CollectionMatch?)

data class CollectionMatch(val kind: CollectionKind, val reason: CollectionReason)

/** Called back from the engine on its scanning thread. Must not throw. */
interface HashSource {
    /** An open descriptor for file [index], owned by the engine from here; `null` if it can't be opened. */
    fun open(index: Int): Int?

    fun onProgress(done: Int, total: Int)

    fun isCancelled(): Boolean
}

/** The library's files and their text. */
interface TidyLibrary {
    /** Rescans the linked folders and returns every file found. */
    suspend fun files(): List<TidyFile>

    /** Up to [maxChars] of [file]'s plain text, or `null` when it has none or can't be read. */
    suspend fun plainText(file: TidyFile, maxChars: Int): String?
}

/** The platform side: descriptors, small grey previews, and deletion the user confirmed. */
interface TidyFiles {
    /** Blocking. A readable descriptor the caller then owns, or `null`. */
    fun openDescriptor(file: TidyFile): Int?

    /** Blocking. [file] decoded at [side] x [side] pixels as one luminance byte per pixel. */
    fun grayThumbnail(file: TidyFile, side: Int): GrayImage?

    val canDelete: Boolean

    /** Re-reads both files, keeps [retained], and deletes [file] only if their bytes still match. Blocking. */
    fun deleteDuplicate(file: TidyFile, retained: TidyFile): Boolean
}

/** Small persisted key-value storage for the scan caches. Blocking. */
interface TidyStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class GrayImage(val width: Int, val height: Int, val pixels: ByteArray)
