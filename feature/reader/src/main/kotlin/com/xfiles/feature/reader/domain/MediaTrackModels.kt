package com.xfiles.feature.reader.domain

import androidx.media3.common.Tracks

/** Subtitle formats the player can name. Bitmap formats are picture-based and cannot be restyled. */
enum class MediaSubtitleFormat(val label: String, val bitmap: Boolean = false) {
    Srt("SRT"),
    Vtt("VTT"),
    Ass("ASS"),
    Pgs("PGS", bitmap = true),
    VobSub("VobSub", bitmap = true),
    Dvb("DVB", bitmap = true),
    Ttml("TTML"),
    Tx3g("TX3G"),
    Cea("CC"),
    Other("Text"),
}

/** One audio track in the file, ready to show. [group] is what the player needs to select it. */
data class MediaAudioTrack(
    val id: String,
    val title: String,
    /** Codec and channel layout, such as "AAC · Stereo" or "Dolby Digital · 5.1". */
    val detail: String,
    val selected: Boolean,
    val supported: Boolean,
    /** Why the track can't be chosen, when it can't. */
    val reason: String?,
    val group: Tracks.Group,
)

/** One subtitle track: embedded in the video, or a file the user loaded. */
data class MediaTextTrack(
    val id: String,
    val title: String,
    val format: MediaSubtitleFormat,
    /** Forced, SDH, Default and File, in that order, where they apply. */
    val flags: List<String>,
    val selected: Boolean,
    val supported: Boolean,
    val reason: String?,
    val group: Tracks.Group,
)

data class MediaTrackSet(val audio: List<MediaAudioTrack>, val text: List<MediaTextTrack>)
