package com.xfiles.documents.archive

/**
 * Folder tree over the flat entry list of one archive.
 *
 * Built once, off the main thread, from the engine's entries. Each path
 * segment is a trie edge, so navigating to a folder costs one hash lookup per
 * segment and listing a folder never rescans the archive. Folders that exist
 * only as prefixes of file paths (common in archives without directory
 * records) are created implicitly and gain their metadata if a directory
 * record for them arrives later.
 */
class ArchiveTree private constructor(val root: ArchiveNode, val entryCount: Int) {

    /** The node at [folderPath] (`""` for the root), or null if it does not exist. */
    fun find(folderPath: String): ArchiveNode? {
        if (folderPath.isEmpty()) return root
        var node = root
        for (segment in folderPath.split('/')) {
            node = node.childMap[segment] ?: return null
        }
        return node
    }

    /**
     * Case-insensitive substring match over full paths, depth-first, stopping
     * at [limit] hits. Call from a background dispatcher: it visits every node.
     */
    fun search(query: String, limit: Int = SEARCH_LIMIT): List<ArchiveNode> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<ArchiveNode>()
        val stack = ArrayDeque<ArchiveNode>()
        stack.addLast(root)
        while (stack.isNotEmpty() && hits.size < limit) {
            val node = stack.removeLast()
            if (node !== root && node.path.contains(needle, ignoreCase = true)) hits += node
            // Pushed in reverse so the natural-order traversal yields hits in tree order.
            for (child in node.sortedChildren.asReversed()) stack.addLast(child)
        }
        return hits
    }

    companion object {
        const val SEARCH_LIMIT = 500

        /** Builds the tree from engine entries. Unsafe or empty paths are skipped. */
        fun build(entries: List<ArchiveEntry>): ArchiveTree {
            val root = ArchiveNode(name = "", path = "", isDirectory = true)
            var count = 0
            for (entry in entries) {
                val segments = entry.path.trim('/').split('/').filter { it.isNotEmpty() && it != "." }
                if (segments.isEmpty() || segments.any { it == ".." }) continue
                var node = root
                var blocked = false
                for (i in 0 until segments.lastIndex) {
                    node = node.childOrCreate(segments[i], node.path.joinPath(segments[i]), isDirectory = true, entry = null)
                    if (!node.isDirectory) {
                        blocked = true // a file already holds this name; its contents cannot be reached
                        break
                    }
                }
                if (blocked) continue
                count++
                val leafName = segments.last()
                val leafPath = node.path.joinPath(leafName)
                val existing = node.childMap[leafName]
                if (entry.isDirectory && existing != null && existing.isDirectory && existing.entry == null) {
                    // A directory record arrived after the folder was created implicitly: keep its metadata.
                    existing.entry = entry
                } else {
                    // When a file and a folder share a name, whichever came first keeps the slot.
                    node.childOrCreate(leafName, leafPath, isDirectory = entry.isDirectory, entry = entry)
                }
            }
            return ArchiveTree(root, count)
        }

        private fun String.joinPath(segment: String): String = if (isEmpty()) segment else "$this/$segment"
    }
}

/** A file or folder in an [ArchiveTree]. [path] is the full path without a trailing slash. */
class ArchiveNode internal constructor(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    entry: ArchiveEntry? = null,
) {
    /** The archive record for this node; null for a folder that exists only as a path prefix. */
    var entry: ArchiveEntry? = entry
        internal set

    internal val childMap = HashMap<String, ArchiveNode>()

    /** Children, folders first, then natural name order. Computed once, after the build. */
    val sortedChildren: List<ArchiveNode> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        childMap.values.sortedWith(FolderFirstNatural)
    }

    val size: Long get() = entry?.size ?: 0L

    internal fun childOrCreate(name: String, path: String, isDirectory: Boolean, entry: ArchiveEntry?): ArchiveNode {
        childMap[name]?.let { return it }
        val node = ArchiveNode(name, path, isDirectory, entry)
        childMap[name] = node
        return node
    }
}

/** Folders before files; within each group, [NaturalOrder]. */
val FolderFirstNatural: Comparator<ArchiveNode> =
    Comparator { a, b ->
        if (a.isDirectory != b.isDirectory) (if (a.isDirectory) -1 else 1)
        else NaturalOrder.compare(a.name, b.name)
    }

/**
 * Case-insensitive natural order: runs of digits compare by numeric value, so
 * `img2` sorts before `img10`. Leading zeros do not change the value; when the
 * values tie, the run with fewer digits sorts first (`file1` before `file01`),
 * so distinct names never compare equal.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigitAscii() && cb.isDigitAscii()) {
                val startA = i
                while (i < a.length && a[i].isDigitAscii()) i++
                val startB = j
                while (j < b.length && b[j].isDigitAscii()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length.compareTo(numB.length)
                val byValue = numA.compareTo(numB)
                if (byValue != 0) return byValue
                // Equal values with different padding (file1 vs file01): the shorter run first.
                val byWidth = (i - startA).compareTo(j - startB)
                if (byWidth != 0) return byWidth
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun Char.isDigitAscii(): Boolean = this in '0'..'9'
}

/** One entry as Kotlin sees it, mirroring the engine's `NativeArchiveEntry`. */
data class ArchiveEntry(
    val path: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val encrypted: Boolean,
    /** MS-DOS time (low 16 bits) and date (high 16 bits). */
    val modifiedDos: Int,
)
