package com.xfiles.documents.book

/**
 * The word around [offset] as a half-open range, or null if [text] is empty. A character that is
 * not part of a word (space, punctuation) selects just itself.
 */
fun wordRangeAt(text: String, offset: Int): IntRange? {
    if (text.isEmpty()) return null
    val at = offset.coerceIn(0, text.length - 1)
    if (!isWordChar(text[at])) return at until at + 1
    var start = at
    while (start > 0 && isWordChar(text[start - 1])) start--
    var end = at + 1
    while (end < text.length && isWordChar(text[end])) end++
    return start until end
}

/**
 * Grows [anchor] (a word selected by a long press) to also cover the word at [focusOffset], as a
 * drag does. Never shrinks [anchor].
 */
fun extendWordSelection(anchor: IntRange, focusOffset: Int, text: String): IntRange {
    val focus = wordRangeAt(text, focusOffset) ?: return anchor
    return minOf(anchor.first, focus.first) until maxOf(anchor.last + 1, focus.last + 1)
}

/** The range between two offsets, clamped to [length], in either order; null if empty. */
fun orderedSelection(a: Int, b: Int, length: Int): IntRange? {
    val start = minOf(a, b).coerceIn(0, length)
    val end = maxOf(a, b).coerceIn(0, length)
    return if (start < end) start until end else null
}

/** A character position in the book: a block and an offset into the block's text. Ordered in reading order. */
data class TextPoint(val block: Int, val offset: Int) : Comparable<TextPoint> {
    override fun compareTo(other: TextPoint): Int =
        if (block != other.block) block.compareTo(other.block) else offset.compareTo(other.offset)
}

/** The part of one block a selection covers: [start] until [end], half-open. */
data class BlockSpan(val block: Int, val start: Int, val end: Int)

/** Most blocks one selection may cover, which bounds the work of a drag. */
const val MAX_SELECTED_BLOCKS = 200

/**
 * The spans between [a] and [b] in either order, in reading order. [lengthOf] is the text length of a
 * block; blocks the selection leaves empty are skipped.
 */
fun selectionSpans(a: TextPoint, b: TextPoint, lengthOf: (Int) -> Int): List<BlockSpan> {
    val first = minOf(a, b)
    val last = maxOf(a, b)
    val out = ArrayList<BlockSpan>()
    var block = first.block
    while (block <= last.block && block - first.block < MAX_SELECTED_BLOCKS) {
        val length = lengthOf(block).coerceAtLeast(0)
        val start = if (block == first.block) first.offset.coerceIn(0, length) else 0
        val end = if (block == last.block) last.offset.coerceIn(0, length) else length
        if (start < end) out.add(BlockSpan(block, start, end))
        block++
    }
    return out
}

/** The range of [block] (of [length] characters) that the selection between [a] and [b] covers, or null if none. */
fun spanInBlock(a: TextPoint, b: TextPoint, block: Int, length: Int): IntRange? {
    val first = minOf(a, b)
    val last = maxOf(a, b)
    if (block < first.block || block > last.block) return null
    val start = if (block == first.block) first.offset else 0
    val end = if (block == last.block) last.offset else length
    return orderedSelection(start, end, length)
}

/** The offset at [offset] moved to the start of its word, as a selection that starts there snaps. */
fun wordStartAt(text: String, offset: Int): Int = wordRangeAt(text, offset)?.first ?: 0

/** The offset at [offset] moved to the end of its word, as a selection that ends there snaps. */
fun wordEndAt(text: String, offset: Int): Int = wordRangeAt(text, offset)?.let { it.last + 1 } ?: 0

private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’'
