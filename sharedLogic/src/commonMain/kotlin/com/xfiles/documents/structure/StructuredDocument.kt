package com.xfiles.documents.structure

import com.xfiles.documents.book.BookManifest

data class StructuredDocument(
    val info: DocumentInfo,
    val blocks: List<ContentBlock>,
    val images: Map<String, ByteArray>,
    val truncated: Boolean,
    /** An EPUB's contents, chapters and footnotes; null for every other format. */
    val book: BookManifest? = null,
) {
    fun plainText(limit: Int = 32_000): String = buildString {
        fun addLine(value: String) {
            if (value.isBlank() || length >= limit) return
            if (isNotEmpty() && length + 1 < limit) append('\n')
            val end = minOf(value.length, limit - length)
            append(value, 0, if (end in 1 until value.length && value[end - 1].isHighSurrogate()) end - 1 else end)
        }
        for (block in blocks) {
            if (length >= limit) break
            when (block) {
                is ContentBlock.Heading -> addLine(block.text)
                is ContentBlock.Paragraph -> addLine(block.text)
                is ContentBlock.ListItem -> addLine(block.text)
                is ContentBlock.Quote -> addLine(block.text)
                is ContentBlock.Code -> addLine(block.text)
                is ContentBlock.TableRow -> block.cells.forEach(::addLine)
                is ContentBlock.Entry -> addLine(block.path)
                is ContentBlock.Section -> addLine(block.title)
                is ContentBlock.Shape -> {
                    block.shape.paragraphs.forEach { addLine(it.text) }
                    block.shape.table.forEach { row -> row.forEach(::addLine) }
                }
                else -> Unit
            }
        }
    }
}
