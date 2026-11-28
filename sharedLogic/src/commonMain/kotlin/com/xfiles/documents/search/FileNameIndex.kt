package com.xfiles.documents.search

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ranked, typo-tolerant matching of file names; a port of EzFiles' engine
 * (document-search/names.rs), so both apps rank the same way.
 *
 * Names are prepared once ([FileNameIndex]); each keystroke then scores every
 * name in one pass. A name matches when every word of the query matches one of
 * its words, by (best first): the whole word, a prefix of it, a part of it, a
 * bounded Damerau-Levenshtein distance (one typo from 4 letters, two from 8),
 * a typo in a word still being typed, or trigram similarity. Words with digits
 * never match fuzzily, so "2025" doesn't find "2024".
 *
 * Pure Kotlin with no Android types, so it can run on a background dispatcher.
 */
class FileNameIndex(names: List<String>) {
    private val prepared: List<PreparedName> = names.map { name ->
        val words = nameWords(name).map { word -> PreparedWord(word, word.hasDigit, trigrams(word.text)) }
        PreparedName(words, fold(name), words.joinToString("") { it.text })
    }

    /**
     * Positions of the matching names, best first, at most [limit]. Equal scores
     * keep the original order. A blank query matches every name, in order.
     */
    fun search(query: String, limit: Int = Int.MAX_VALUE): List<Int> {
        if (limit <= 0) return emptyList()
        val queryWords = nameWords(query).map { word -> PreparedWord(word, word.hasDigit, trigrams(word.text)) }
        if (queryWords.isEmpty()) {
            val folded = fold(query.trim())
            val all = prepared.indices.filter { folded.isEmpty() || prepared[it].folded.contains(folded) }
            return all.take(limit)
        }
        val queryCompact = queryWords.joinToString("") { it.text }
        val scratch = Scratch()
        val scored = ArrayList<Pair<Int, Float>>()
        for (i in prepared.indices) {
            val score = scoreName(prepared[i], queryWords, queryCompact, scratch) ?: continue
            scored.add(i to score)
        }
        // sortedByDescending is stable: equal scores keep the caller's order.
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private class PreparedName(val words: List<PreparedWord>, val folded: String, val compact: String)

    private class PreparedWord(val word: NameWord, val hasDigit: Boolean, val grams: LongArray) {
        val text: String get() = word.text
    }

    private class Scratch {
        var r0 = IntArray(0)
        var r1 = IntArray(0)
        var r2 = IntArray(0)

        fun ensure(width: Int) {
            if (r0.size < width) {
                r0 = IntArray(width)
                r1 = IntArray(width)
                r2 = IntArray(width)
            }
        }
    }

    private companion object {
        const val EXACT = 1.0f
        const val PART = 0.7f
        const val COMPACT_PART = 0.6f
        const val ONE_TYPO = 0.6f
        const val TWO_TYPOS = 0.45f
        const val TYPO_PREFIX = 0.45f
        const val TRIGRAM_WEIGHT = 0.5f
        const val TRIGRAM_MIN = 0.55f

        fun scoreName(name: PreparedName, query: List<PreparedWord>, queryCompact: String, scratch: Scratch): Float? {
            var total = 0f
            for (q in query) {
                var best = 0f
                for ((position, w) in name.words.withIndex()) {
                    val raw = scoreWord(q, w, scratch)
                    // Earlier words weigh a little more: "report" ranks "Report 2024" above "Q3 report".
                    val score = if (raw > 0f) raw - 0.002f * min(position, 10) else 0f
                    best = max(best, score)
                    if (best >= EXACT) break
                }
                // Words run together in the name: "annualreport" in "AnnualReport".
                if (best <= 0f && name.compact.contains(q.text)) best = COMPACT_PART
                if (best <= 0f) return null
                total += best
            }
            var score = total / query.size
            if (name.compact.startsWith(queryCompact)) {
                score += 0.1f
            } else if (name.compact.contains(queryCompact)) {
                score += 0.05f
            }
            // Among equals, shorter names are closer to what was typed.
            score -= 0.0005f * min(name.compact.length, 200)
            return score
        }

        fun scoreWord(q: PreparedWord, w: PreparedWord, scratch: Scratch): Float {
            val qt = q.text
            val wt = w.text
            if (wt.startsWith(qt)) {
                // Exact is 1.0; a prefix scores more the more of the word it covers.
                return 0.8f + 0.2f * (qt.length.toFloat() / wt.length.toFloat())
            }
            val qlen = qt.length
            if (qlen >= 2 && wt.contains(qt)) return PART
            if (qlen < 4 || q.hasDigit || w.hasDigit) return 0f
            val maxTypos = if (qlen >= 8) 2 else 1
            val distance = boundedOsa(qt, wt, maxTypos, scratch)
            if (distance != null) return if (distance <= 1) ONE_TYPO else TWO_TYPOS
            // A typo in a word still being typed: "invl" ... "invoi" against "invoice".
            if (qlen >= 5 && wt.length > qlen) {
                if (boundedOsa(qt, wt.substring(0, qlen), 1, scratch) != null) return TYPO_PREFIX
            }
            if (qlen >= 6) {
                val sim = dice(q.grams, w.grams)
                if (sim >= TRIGRAM_MIN) return TRIGRAM_WEIGHT * sim
            }
            return 0f
        }

        /** Optimal string alignment distance, or null once it must exceed [max]. */
        fun boundedOsa(a: String, b: String, max: Int, scratch: Scratch): Int? {
            if (abs(a.length - b.length) > max) return null
            val width = b.length + 1
            scratch.ensure(width)
            var prev2 = scratch.r0
            var prev = scratch.r1
            var cur = scratch.r2
            for (j in 0 until width) prev[j] = j
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] != b[j - 1]) 1 else 0
                    var value = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        value = min(value, prev2[j - 2] + 1)
                    }
                    cur[j] = value
                    rowMin = min(rowMin, value)
                }
                if (rowMin > max) return null
                val spare = prev2
                prev2 = prev
                prev = cur
                cur = spare
            }
            val d = prev[b.length]
            return if (d <= max) d else null
        }

        /** Sorted, de-duplicated trigrams of a word padded with a boundary mark. */
        fun trigrams(word: String): LongArray {
            if (word.length < 2) return LongArray(0)
            val pad = '\u0001'
            val padded = "$pad$word$pad"
            val raw = LongArray(padded.length - 2) { i ->
                (padded[i].code.toLong() shl 42) or (padded[i + 1].code.toLong() shl 21) or padded[i + 2].code.toLong()
            }
            raw.sort()
            var n = 0
            for (i in raw.indices) {
                if (i == 0 || raw[i] != raw[i - 1]) raw[n++] = raw[i]
            }
            return raw.copyOf(n)
        }

        /** Dice similarity of two sorted trigram sets. */
        fun dice(a: LongArray, b: LongArray): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            var i = 0
            var j = 0
            var shared = 0
            while (i < a.size && j < b.size) {
                when {
                    a[i] < b[j] -> i++
                    a[i] > b[j] -> j++
                    else -> {
                        shared++
                        i++
                        j++
                    }
                }
            }
            return 2f * shared / (a.size + b.size).toFloat()
        }

    }
}

/** One word of a file name, folded; [hasDigit] when it contains a digit. */
internal class NameWord(val text: String, val hasDigit: Boolean)

private enum class CharClass { Lower, Upper, Digit, Other }

private fun classOf(c: Char): CharClass = when {
    c.isDigit() -> CharClass.Digit
    c.isUpperCase() -> CharClass.Upper
    c.isLetter() || isCombiningMark(c) -> CharClass.Lower
    else -> CharClass.Other
}

/** Ideographs and kana are written without spaces, so each is a word of its own. */
private fun isUnspaced(c: Char): Boolean = when (c.code) {
    in 0x3040..0x30FF, in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF -> true
    else -> false
}

/**
 * The words of a file name, folded: split at separators (`_ - . space` and any
 * other punctuation), between letters and digits, and at camelCase humps
 * ("AnnualReport2025" becomes annual, report, 2025; "PDFFile" becomes pdf, file).
 */
internal fun nameWords(name: String): List<NameWord> {
    val words = ArrayList<NameWord>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            val text = current.toString()
            words.add(NameWord(text, text.any { it.isDigit() }))
            current.clear()
        }
    }
    for (i in name.indices) {
        val c = name[i]
        val here = classOf(c)
        if (isUnspaced(c)) {
            flush()
            pushFolded(c, current)
            flush()
            continue
        }
        if (here == CharClass.Other) {
            flush()
            continue
        }
        if (i > 0) {
            val before = classOf(name[i - 1])
            val nextLower = i + 1 < name.length && classOf(name[i + 1]) == CharClass.Lower
            val split = when {
                before == CharClass.Lower && here == CharClass.Upper -> true
                before == CharClass.Upper && here == CharClass.Upper -> nextLower
                before == CharClass.Digit && (here == CharClass.Lower || here == CharClass.Upper) -> true
                (before == CharClass.Lower || before == CharClass.Upper) && here == CharClass.Digit -> true
                else -> false
            }
            if (split) flush()
        }
        pushFolded(c, current)
    }
    flush()
    return words
}

/** Case and diacritic folding, so "Resume", "RESUME" and "Résumé" compare equal. */
internal fun fold(text: String): String {
    val out = StringBuilder(text.length)
    for (c in text) pushFolded(c, out)
    return out.toString()
}

private fun pushFolded(c: Char, out: StringBuilder) {
    if (c.code < 128) {
        out.append(c.lowercaseChar())
        return
    }
    if (isCombiningMark(c)) return
    for (lower in c.lowercase()) {
        if (isCombiningMark(lower)) continue
        val base = stripAccent(lower)
        if (base != null) out.append(base) else out.append(lower)
    }
}

private fun isCombiningMark(c: Char): Boolean = when (c.code) {
    in 0x0300..0x036F, in 0x1AB0..0x1AFF, in 0x1DC0..0x1DFF, in 0x20D0..0x20FF, in 0xFE20..0xFE2F -> true
    else -> false
}

/** The unaccented spelling of a lowercase letter (Latin-1 and Latin Extended-A), where it has one. */
private fun stripAccent(c: Char): String? = when (c.code) {
    0xE0, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0x101, 0x103, 0x105 -> "a"
    0xE6 -> "ae"
    0xE7, 0x107, 0x109, 0x10B, 0x10D -> "c"
    0x10F, 0x111, 0xF0 -> "d"
    0xE8, 0xE9, 0xEA, 0xEB, 0x113, 0x115, 0x117, 0x119, 0x11B -> "e"
    0x11D, 0x11F, 0x121, 0x123 -> "g"
    0x125, 0x127 -> "h"
    0xEC, 0xED, 0xEE, 0xEF, 0x129, 0x12B, 0x12D, 0x12F, 0x131 -> "i"
    0x133 -> "ij"
    0x135 -> "j"
    0x137, 0x138 -> "k"
    0x13A, 0x13C, 0x13E, 0x140, 0x142 -> "l"
    0xF1, 0x144, 0x146, 0x148, 0x149, 0x14B -> "n"
    0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF8, 0x14D, 0x14F, 0x151 -> "o"
    0x153 -> "oe"
    0x155, 0x157, 0x159 -> "r"
    0x15B, 0x15D, 0x15F, 0x161, 0x219, 0x17F -> "s"
    0xDF -> "ss"
    0x163, 0x165, 0x167, 0x21B -> "t"
    0xFE -> "th"
    0xF9, 0xFA, 0xFB, 0xFC, 0x169, 0x16B, 0x16D, 0x16F, 0x171, 0x173 -> "u"
    0x175 -> "w"
    0xFD, 0xFF, 0x177 -> "y"
    0x17A, 0x17C, 0x17E -> "z"
    else -> null
}
