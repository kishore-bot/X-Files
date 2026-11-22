package com.xfiles.feature.reader.domain

import javax.inject.Inject

/** Where a tap on a slide link leads, resolved against the slide being shown. */
internal sealed interface SlideLinkTarget {
    /** Show the slide at [index] (0-based). */
    data class Slide(val index: Int) : SlideLinkTarget

    /** Open [url] in another app, once the reader has confirmed it. */
    data class Web(val url: String) : SlideLinkTarget

    /** Nothing to do: an unknown target, or a jump past either end of the deck. */
    data object Nowhere : SlideLinkTarget
}

/**
 * Turns a link from the slide model (`slide:N`, `slide:next`, `slide:previous`, `slide:first`,
 * `slide:last`, or a web or mail URL) into a [SlideLinkTarget]. Only http, https and mailto links
 * are ever offered, whatever the document says.
 */
internal class ResolveSlideLinkUseCase @Inject constructor() {
    operator fun invoke(link: String?, current: Int, count: Int): SlideLinkTarget {
        val raw = link?.trim().orEmpty()
        if (raw.isEmpty() || count <= 0) return SlideLinkTarget.Nowhere
        if (raw.startsWith(SLIDE_PREFIX, ignoreCase = true)) {
            val target = raw.substring(SLIDE_PREFIX.length)
            val index = when (target) {
                "next" -> current + 1
                "previous" -> current - 1
                "first" -> 0
                "last" -> count - 1
                else -> target.toIntOrNull()?.let { it - 1 } ?: return SlideLinkTarget.Nowhere
            }
            return if (index in 0 until count) SlideLinkTarget.Slide(index) else SlideLinkTarget.Nowhere
        }
        val web = raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("mailto:", ignoreCase = true)
        return if (web) SlideLinkTarget.Web(raw) else SlideLinkTarget.Nowhere
    }

    private companion object {
        const val SLIDE_PREFIX = "slide:"
    }
}
