package com.xfiles.core.documents

import kotlin.math.pow

/** A visit loses half its weight after this long, so a file opened daily outranks one opened once a month. */
const val FRECENCY_HALF_LIFE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * Sum of the visits' decayed weights at [now]: each visit counts once, and counts half as much per
 * [halfLifeMillis] of age. Visits from the future count as fresh.
 */
fun frecencyScore(visits: List<Long>, now: Long, halfLifeMillis: Long = FRECENCY_HALF_LIFE_MILLIS): Double {
    var score = 0.0
    for (visit in visits) {
        score += 0.5.pow((now - visit).coerceAtLeast(0L).toDouble() / halfLifeMillis)
    }
    return score
}

/**
 * Orders [this] by descending frecency. Each score is computed once; the sort is stable, so items that
 * tie (for example files with no recorded visit times) keep their incoming, newest-first order.
 */
fun <T> List<T>.byFrecency(
    now: Long,
    visitsOf: (T) -> List<Long>,
    halfLifeMillis: Long = FRECENCY_HALF_LIFE_MILLIS,
): List<T> {
    if (size < 2) return this
    val scored = map { item -> item to frecencyScore(visitsOf(item), now, halfLifeMillis) }
    return scored.sortedByDescending { it.second }.map { it.first }
}
