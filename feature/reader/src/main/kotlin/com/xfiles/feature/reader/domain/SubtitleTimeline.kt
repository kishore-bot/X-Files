package com.xfiles.feature.reader.domain

import com.xfiles.engine.ffi.NativeSubtitleCue

internal class SubtitleTimeline(private val cues: List<NativeSubtitleCue>) {
    private val prefixEnd = LongArray(cues.size)
    init {
        var longest = 0L
        cues.forEachIndexed { index, cue ->
            longest = maxOf(longest, cue.endMs.toLong())
            prefixEnd[index] = longest
        }
    }

    fun at(mediaMs: Long, offsetMs: Long): List<String> {
        val time = mediaMs - offsetMs
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) / 2
            if (cues[middle].startMs.toLong() <= time) low = middle + 1 else high = middle
        }
        val text = mutableListOf<String>()
        var index = low - 1
        while (index >= 0 && prefixEnd[index] > time) {
            val cue = cues[index--]
            if (cue.endMs.toLong() > time) text += cue.text
        }
        text.reverse()
        return text
    }
}
