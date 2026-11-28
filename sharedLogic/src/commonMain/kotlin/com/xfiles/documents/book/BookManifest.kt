package com.xfiles.documents.book

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A chapter in reading order. [firstBlock] is where its text starts in the document's blocks, or null if not streamed. */
data class BookChapter(val href: String, val size: Long, val firstBlock: Int?)

/** A table-of-contents entry: [href] is an archive path with an optional `#fragment`. */
data class BookTocEntry(val title: String, val href: String, val depth: Int)

/**
 * The engine's description of an EPUB, read once the book has streamed: metadata, chapters with
 * their block starts, the contents, and footnote texts keyed by `archive path#id`.
 */
data class BookManifest(
    val title: String,
    val author: String,
    val language: String,
    val rtl: Boolean,
    val fixed: Boolean,
    val chapters: List<BookChapter>,
    val toc: List<BookTocEntry>,
    /** Block each element id starts in, keyed by `archive path#id` with the fragment decoded. */
    val anchors: Map<String, Int> = emptyMap(),
    val notes: Map<String, String>,
) {
    /** Chapters that have a known start, sorted by it. Binary search runs over this. */
    private val starts: List<Pair<Int, Int>> by lazy {
        chapters.withIndex()
            .mapNotNull { (index, chapter) -> chapter.firstBlock?.let { it to index } }
            .sortedBy { it.first }
    }

    private val chapterByPath: Map<String, Int> by lazy {
        chapters.withIndex().associate { (index, chapter) -> chapter.href to index }
    }

    /**
     * Block that [href] (`archive path` or `archive path#id`) starts at: the anchor's block when the
     * id is known, else the start of its file, or null if the file is not in the book or not streamed.
     */
    fun blockOf(href: String): Int? {
        val hash = href.indexOf('#')
        if (hash >= 0) anchors[href]?.let { return it }
        val path = if (hash >= 0) href.substring(0, hash) else href
        return chapterByPath[path]?.let { chapters[it].firstBlock }
    }

    /** Index in [chapters] of the chapter that holds [block], or -1 before the first known chapter. */
    fun chapterIndexAt(block: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid].first <= block) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (found >= 0) starts[found].second else -1
    }

    /** The contents title of chapter [index], else the file name without its extension. */
    fun chapterTitle(index: Int): String {
        val href = chapters.getOrNull(index)?.href ?: return ""
        toc.firstOrNull { it.href.substringBefore('#') == href }?.let { return it.title }
        return href.substringAfterLast('/').substringBeforeLast('.')
    }

    companion object {
        /** The resource the engine serves this description under. */
        const val RESOURCE = "ezfiles:book.json"

        /** Prefix of footnote links in block spans; the rest is the `archive path#id` key of [notes]. */
        const val NOTE_PREFIX = "note:"

        /** Prefix of links to another place in the book; the rest is an href for [blockOf]. */
        const val LINK_PREFIX = "book:"

        const val MAX_JSON_CHARS = 8 * 1024 * 1024
        const val MAX_CHAPTERS = 5_000
        const val MAX_TOC = 5_000
        const val MAX_TOC_DEPTH = 6
        const val MAX_ANCHORS = 20_000
        const val MAX_NOTES = 2_000
        const val MAX_NOTE_CHARS = 1_200

        /** The manifest, or null if [json] is too large, malformed, or not a manifest. Never throws. */
        fun parseOrNull(json: String): BookManifest? {
            if (json.length > MAX_JSON_CHARS) return null
            return try {
                parse(json)
            } catch (error: IllegalArgumentException) {
                // kotlinx.serialization reports malformed input as an IllegalArgumentException.
                null
            }
        }

        private fun parse(json: String): BookManifest {
            val root = Json.parseToJsonElement(json).jsonObject
            val chapters = root["chapters"].items().take(MAX_CHAPTERS).mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val href = obj.string("href")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                BookChapter(
                    href = href,
                    size = obj.long("size")?.coerceAtLeast(0L) ?: 0L,
                    firstBlock = obj.long("block")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
                )
            }
            val toc = root["toc"].items().take(MAX_TOC).mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val title = obj.string("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val href = obj.string("href")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                BookTocEntry(title, href, (obj.long("depth") ?: 0L).coerceIn(0L, MAX_TOC_DEPTH.toLong()).toInt())
            }
            val anchors = (root["anchors"] as? JsonObject)?.let { obj ->
                obj.entries.asSequence()
                    .mapNotNull { (key, value) ->
                        val block = (value as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..Int.MAX_VALUE }
                        block?.let { key to it.toInt() }
                    }
                    .take(MAX_ANCHORS)
                    .toMap()
            }.orEmpty()
            val notes = (root["notes"] as? JsonObject)?.let { obj ->
                obj.entries.asSequence()
                    .mapNotNull { (key, value) ->
                        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        text?.let { key to it.take(MAX_NOTE_CHARS) }
                    }
                    .take(MAX_NOTES)
                    .toMap()
            }.orEmpty()
            return BookManifest(
                title = root.string("title").orEmpty(),
                author = root.string("author").orEmpty(),
                language = root.string("language").orEmpty(),
                rtl = (root["rtl"] as? JsonPrimitive)?.booleanOrNull == true,
                fixed = (root["fixed"] as? JsonPrimitive)?.booleanOrNull == true,
                chapters = chapters,
                toc = toc,
                anchors = anchors,
                notes = notes,
            )
        }

        private fun JsonElement?.items(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    }
}

/**
 * A stable key for one book across launches, from what identifies its content: the file name and
 * size, and for an EPUB its title, author and chapter sizes. FNV-1a 64 over the UTF-8 bytes.
 */
fun bookKey(name: String, byteSize: Long, manifest: BookManifest?): String {
    val text = buildString {
        append(name).append('|').append(byteSize)
        if (manifest != null) {
            append('|').append(manifest.title).append('|').append(manifest.author)
            manifest.chapters.forEach { append('|').append(it.size) }
        }
    }
    var hash = 0xcbf29ce484222325UL.toLong()
    for (byte in text.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
