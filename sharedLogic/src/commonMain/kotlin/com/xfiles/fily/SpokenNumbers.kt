package com.xfiles.fily

/** "five", "5" or "5th" to 5; null when there is no number. */
fun spokenNumber(value: String?): Int? {
    val text = value?.trim()?.lowercase() ?: return null
    text.filter(Char::isDigit).takeIf(String::isNotEmpty)?.let { return it.toIntOrNull() }
    return NUMBER_WORDS.indexOf(text).takeIf { it > 0 }
}

private val NUMBER_WORDS = listOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
)
