package com.xfiles.documents

/**
 * Parses one-based page numbers and inclusive ranges into zero-based pages.
 * Selection order is retained and repeated pages are included only once.
 */
fun parsePdfPageSelection(input: String, pageCount: Int): List<Int>? {
    if (input.length > 1_000 || pageCount < 1) return null
    val chosen = LinkedHashSet<Int>()
    for (part in input.split(',')) {
        val pair = part.split('-', limit = 2)
        val start = pair[0].trim().toIntOrNull() ?: return null
        val end = if (pair.size == 1) start else pair[1].trim().toIntOrNull() ?: return null
        if (start !in 1..pageCount || end !in start..pageCount || end - start >= 500) return null
        for (page in start..end) {
            chosen.add(page - 1)
            if (chosen.size > 500) return null
        }
    }
    return chosen.toList()
}
