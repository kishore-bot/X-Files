package com.xfiles.documents

/** What an "at a glance" item is, so the UI can act on it: a date to add, an amount to copy, a number to call…. */
enum class GlanceKind { Date, Amount, Phone, Email, Link, Reference }

/**
 * A thing to act on found in a document. [text] is as written; [value] is the normalised form the action uses:
 * `YYYY-MM-DD` for a date, digits for a phone, an address, a URL, or the reference itself.
 */
data class GlanceItem(val kind: GlanceKind, val text: String, val value: String, val label: String? = null)

data class DocumentAnalysis(
    val keySentences: List<String>,
    val keywords: List<String>,
    val language: String?,
    val wordCount: UInt,
    val readingMinutes: UInt,
    val items: List<GlanceItem> = emptyList(),
)
