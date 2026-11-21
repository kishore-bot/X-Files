package com.xfiles.documents.book

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class HighlightColor { Yellow, Green, Blue, Pink }

/** Text the reader marked. [block] is the document block, and [start]/[end] the range inside its text. */
@Serializable
data class Highlight(
    val id: String,
    val block: Int,
    val start: Int,
    val end: Int,
    val color: HighlightColor,
    val quote: String,
    val createdAt: Long,
)

/** A note on a range of text; [text] is what the reader wrote. */
@Serializable
data class BookNote(
    val id: String,
    val block: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val quote: String,
    val createdAt: Long,
)

/** A place in the book, identified by its block. */
@Serializable
data class Bookmark(val id: String, val block: Int, val label: String, val createdAt: Long)

/** What paints over a range of one block's text. */
sealed interface TextMark {
    data class Highlighted(val id: String, val color: HighlightColor) : TextMark

    data class Noted(val id: String) : TextMark
}

enum class NotebookKind { Bookmark, Highlight, Note }

/** One line of the notebook: [title] is the quote or label, [detail] the note text, if any. */
data class NotebookEntry(
    val kind: NotebookKind,
    val id: String,
    val block: Int,
    val start: Int,
    val title: String,
    val detail: String,
    val color: HighlightColor?,
    val createdAt: Long,
)

/**
 * Everything the reader keeps for one book, as immutable values. Every change returns a new
 * [BookAnnotations]; lists are capped so a book's file stays small, dropping the oldest first.
 */
@Serializable
data class BookAnnotations(
    val highlights: List<Highlight> = emptyList(),
    val notes: List<BookNote> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    /** The block the reader was last at; 0 for a book not opened before. */
    val position: Int = 0,
    /** Reading settings this book uses instead of the global ones; null follows the global settings. */
    val settings: ReadingSettings? = null,
) {
    fun withSettings(override: ReadingSettings?): BookAnnotations =
        if (override == settings) this else copy(settings = override?.sanitized())

    /** Adds [highlight]; an identical range is replaced, so one range keeps one colour. */
    fun withHighlight(highlight: Highlight): BookAnnotations = copy(
        highlights = (highlights.filterNot { it.block == highlight.block && it.start == highlight.start && it.end == highlight.end } + highlight)
            .takeLast(MAX_HIGHLIGHTS),
    )

    fun withoutHighlight(id: String): BookAnnotations = copy(highlights = highlights.filterNot { it.id == id })

    fun withNote(note: BookNote): BookAnnotations = copy(
        notes = (notes.filterNot { it.id == note.id } + note).takeLast(MAX_NOTES),
    )

    fun withoutNote(id: String): BookAnnotations = copy(notes = notes.filterNot { it.id == id })

    /** Removes the bookmark on [block] if there is one, otherwise adds one labelled [label] with id [id]. */
    fun withBookmarkToggled(block: Int, label: String, id: String, now: Long): BookAnnotations {
        val existing = bookmarks.firstOrNull { it.block == block }
        return if (existing != null) {
            copy(bookmarks = bookmarks - existing)
        } else {
            copy(bookmarks = (bookmarks + Bookmark(id, block, clip(label), now)).takeLast(MAX_BOOKMARKS))
        }
    }

    fun isBookmarked(block: Int): Boolean = bookmarks.any { it.block == block }

    fun withoutBookmark(id: String): BookAnnotations = copy(bookmarks = bookmarks.filterNot { it.id == id })

    fun withPosition(block: Int): BookAnnotations = if (block == position) this else copy(position = block.coerceAtLeast(0))

    /** Painted marks grouped by block, ready for [SegmentIndex.build] over each block's text. */
    fun marksByBlock(): Map<Int, List<MarkRange<TextMark>>> {
        val out = HashMap<Int, MutableList<MarkRange<TextMark>>>()
        for (highlight in highlights) {
            out.getOrPut(highlight.block) { ArrayList() }
                .add(MarkRange(highlight.start, highlight.end, TextMark.Highlighted(highlight.id, highlight.color)))
        }
        for (note in notes) {
            out.getOrPut(note.block) { ArrayList() }.add(MarkRange(note.start, note.end, TextMark.Noted(note.id)))
        }
        return out
    }

    /** Every bookmark, highlight and note, in reading order. */
    fun notebook(): List<NotebookEntry> {
        val entries = ArrayList<NotebookEntry>(bookmarks.size + highlights.size + notes.size)
        bookmarks.mapTo(entries) {
            NotebookEntry(NotebookKind.Bookmark, it.id, it.block, 0, it.label, "", null, it.createdAt)
        }
        highlights.mapTo(entries) {
            NotebookEntry(NotebookKind.Highlight, it.id, it.block, it.start, it.quote, "", it.color, it.createdAt)
        }
        notes.mapTo(entries) {
            NotebookEntry(NotebookKind.Note, it.id, it.block, it.start, it.quote, it.text, null, it.createdAt)
        }
        return entries.sortedWith(compareBy<NotebookEntry>({ it.block }, { it.start }, { it.createdAt }))
    }

    companion object {
        const val MAX_HIGHLIGHTS = 5_000
        const val MAX_NOTES = 2_000
        const val MAX_BOOKMARKS = 500
        const val MAX_QUOTE_CHARS = 300

        private val json = Json { ignoreUnknownKeys = true }

        fun clip(text: String): String = text.take(MAX_QUOTE_CHARS)

        fun encode(annotations: BookAnnotations): String = json.encodeToString(BookAnnotations.serializer(), annotations)

        /** The stored annotations, or none when [text] is missing or unreadable. */
        fun decodeOrEmpty(text: String?): BookAnnotations {
            if (text.isNullOrBlank()) return BookAnnotations()
            return try {
                json.decodeFromString(BookAnnotations.serializer(), text)
            } catch (error: IllegalArgumentException) {
                BookAnnotations()
            }
        }
    }
}
