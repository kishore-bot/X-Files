package com.xfiles.feature.reader.domain

import com.xfiles.documents.book.BookManifest
import com.xfiles.documents.book.PageIndex
import javax.inject.Inject

/** Where the reader is inside its chapter, in pages. [total] is null until the chapter's last page is cut. */
data class PageInChapter(val page: Int, val total: Int?) {
    val pagesLeft: Int? get() = total?.let { it - page }
}

/** Reading progress: how far through the book a block is, the chapter ticks, and pages within a chapter. */
class BookProgressUseCase @Inject constructor() {
    /** Progress at a block boundary. The boundary after the last block is 100%. */
    fun fraction(position: Int, blockCount: Int): Float =
        if (blockCount <= 0) 0f else position.coerceIn(0, blockCount).toFloat() / blockCount

    fun pageFraction(index: PageIndex, block: Int): Float =
        if (index.pageCount == 0 || !index.covers(block)) fraction(block, index.blockCount)
        else fraction(index.pageEnd(index.pageOf(block)), index.blockCount)

    /** The block reached by the scrubber, including the last block at 100%. */
    fun blockAt(fraction: Float, blockCount: Int): Int {
        if (blockCount <= 1) return 0
        return (fraction.coerceIn(0f, 1f) * blockCount).toInt().coerceIn(0, blockCount - 1)
    }

    fun chapterStarts(manifest: BookManifest?, blockCount: Int): List<Int> =
        manifest?.chapters.orEmpty().mapNotNull { it.firstBlock }
            .filter { it in 0 until blockCount }.distinct().sorted()

    /** The blocks of the chapter [block] is in: from its first block to the one before the next chapter. Null when unknown. */
    fun chapterBlocks(manifest: BookManifest?, block: Int, blockCount: Int): IntRange? {
        if (manifest == null) return null
        val index = manifest.chapterIndexAt(block)
        if (index < 0) return null
        val start = manifest.chapters[index].firstBlock ?: return null
        val next = manifest.chapters.mapNotNull { it.firstBlock }.filter { it > start }.minOrNull()
        return start until (next ?: blockCount)
    }

    /** The page [block] is on, counted from its chapter's first page. The total is known once the chapter's last block is paged. */
    fun pageInChapter(index: PageIndex, chapter: IntRange?, block: Int): PageInChapter? {
        if (chapter == null || chapter.isEmpty() || index.pageCount == 0 || !index.covers(block)) return null
        val first = index.pageOf(chapter.first)
        val page = index.pageOf(block) - first + 1
        val total = if (index.covers(chapter.last)) index.pageOf(chapter.last) - first + 1 else null
        return PageInChapter(page.coerceAtLeast(1), total)
    }
}
