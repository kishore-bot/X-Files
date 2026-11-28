package com.xfiles.documents

/** Formats an elapsed playback time as minutes and seconds, with hours when needed. */
fun formatPlaybackTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1_000L
    val minutes = seconds / 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$remainder"
    else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$remainder"
}
