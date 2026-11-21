package com.xfiles.feature.reader

import com.xfiles.core.documents.FileEntry
import com.xfiles.documents.DocumentKind
import java.util.concurrent.atomic.AtomicInteger

/** How the pictures of a gallery are ordered. Date puts the newest first, as photo apps do. */
enum class GallerySort(val label: String) { Name("Name"), Newest("Newest") }

/**
 * A frozen, sorted sequence of pictures around the one that was opened. Immutable: paging makes a new
 * session with the same [id], so the pager keeps its place while the selected index moves.
 *
 * Lookup is a binary search over the sorted entries (O(log n)); the order is total (name, then URI),
 * so the opened picture is always found exactly once.
 */
class GallerySession private constructor(
    val entries: List<FileEntry>,
    val index: Int,
    /** True when the folder held more than [MAX_ENTRIES] pictures; the window is centred on the opened one. */
    val truncated: Boolean,
    /** Stable across page turns; used as the Compose key so turning pages never recreates the pager. */
    val id: Int,
    private val uris: Set<String>,
) {
    val size: Int get() = entries.size
    val current: FileEntry get() = entries[index]

    /** Whether [uri] is one of this session's pictures (O(1); the set is built once per session). */
    fun contains(uri: String): Boolean = uri in uris

    /** A session positioned on [target]; the same session when it is already there. */
    fun select(target: Int): GallerySession {
        require(target in entries.indices) { "Gallery index out of range" }
        return if (target == index) this else GallerySession(entries, target, truncated, id, uris)
    }

    companion object {
        const val MAX_ENTRIES = 2_000
        private val ids = AtomicInteger(1)

        /**
         * Builds the session for [opened] from [candidates] (any file kinds; only pictures are kept).
         * [opened] is always included, even when the candidates do not list it.
         */
        fun create(
            opened: FileEntry,
            candidates: Collection<FileEntry>,
            sort: GallerySort = GallerySort.Name,
            cap: Int = MAX_ENTRIES,
        ): GallerySession {
            val comparator = comparatorFor(sort)
            val pool = LinkedHashMap<String, FileEntry>(candidates.size + 1)
            for (candidate in candidates) {
                if (candidate.kind == DocumentKind.Image) pool.putIfAbsent(candidate.uri.toString(), candidate)
            }
            // The listing's copy wins over the reader's (which may lack modifiedAt), so the anchor is the entry sorted.
            val anchor = pool.getOrPut(opened.uri.toString()) { opened }
            val sorted = pool.values.sortedWith(comparator)
            val position = sorted.binarySearch(anchor, comparator)
            check(position >= 0) { "Opened picture is missing from its gallery" }
            val id = ids.getAndIncrement()
            if (sorted.size <= cap) {
                return GallerySession(sorted, position, truncated = false, id = id, uris = pool.keys.toSet())
            }
            val start = (position - cap / 2).coerceIn(0, sorted.size - cap)
            val window = sorted.subList(start, start + cap).toList()
            return GallerySession(
                window,
                position - start,
                truncated = true,
                id = id,
                uris = window.mapTo(HashSet(window.size)) { it.uri.toString() },
            )
        }

        /** Used while the folder is still being listed: the opened picture alone. */
        fun single(opened: FileEntry): GallerySession = create(opened, emptyList())
    }
}

private fun comparatorFor(sort: GallerySort): Comparator<FileEntry> {
    val byName = Comparator<FileEntry> { a, b -> naturalCompare(a.name, b.name) }
    val tieBreak = Comparator<FileEntry> { a, b -> a.uri.toString().compareTo(b.uri.toString()) }
    return when (sort) {
        GallerySort.Name -> byName.thenComparing(tieBreak)
        GallerySort.Newest -> compareByDescending<FileEntry> { it.modifiedAt ?: Long.MIN_VALUE }
            .thenComparing(byName)
            .thenComparing(tieBreak)
    }
}

/**
 * Case-insensitive order where digit runs compare by value, so IMG_2 sorts before IMG_10.
 * Returns the same sign for any pair regardless of argument order.
 */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            while (i < a.length && a[i].isDigit()) i++
            val startB = j
            while (j < b.length && b[j].isDigit()) j++
            val digitsA = a.substring(startA, i).trimStart('0')
            val digitsB = b.substring(startB, j).trimStart('0')
            if (digitsA.length != digitsB.length) return digitsA.length.compareTo(digitsB.length)
            val byValue = digitsA.compareTo(digitsB)
            if (byValue != 0) return byValue
        } else {
            val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}
