package com.xfiles.feature.reader

/** How an e-book is laid out: turned a page at a time, or one continuous scroll. Applies to every book. */
enum class BookReadingMode(val label: String) {
    Paged("Paged"),
    Scroll("Scroll");

    companion object {
        val Default: BookReadingMode = Paged

        /** The mode named by a stored [text], or [Default] when it is missing or unknown. */
        fun fromStored(text: String?): BookReadingMode =
            entries.firstOrNull { it.name == text?.trim() } ?: Default
    }
}
