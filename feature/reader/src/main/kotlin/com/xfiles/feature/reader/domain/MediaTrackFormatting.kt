package com.xfiles.feature.reader.domain

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

/** Pure naming rules for tracks, kept free of Android types so they can be unit-tested. */
internal object MediaTrackFormatting {

    fun subtitleFormat(format: Format): MediaSubtitleFormat = when (format.sampleMimeType) {
        MimeTypes.APPLICATION_SUBRIP -> MediaSubtitleFormat.Srt
        MimeTypes.TEXT_VTT -> MediaSubtitleFormat.Vtt
        MimeTypes.TEXT_SSA -> MediaSubtitleFormat.Ass
        MimeTypes.APPLICATION_PGS -> MediaSubtitleFormat.Pgs
        MimeTypes.APPLICATION_VOBSUB -> MediaSubtitleFormat.VobSub
        MimeTypes.APPLICATION_DVBSUBS -> MediaSubtitleFormat.Dvb
        MimeTypes.APPLICATION_TTML -> MediaSubtitleFormat.Ttml
        MimeTypes.APPLICATION_TX3G -> MediaSubtitleFormat.Tx3g
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> MediaSubtitleFormat.Cea
        else -> MediaSubtitleFormat.Other
    }

    /** "English", or the track's own label when there is no language, or "Track N". */
    fun languageName(format: Format, fallbackIndex: Int): String {
        val tag = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
        val language = tag?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
        return language ?: format.label?.takeIf { it.isNotBlank() } ?: "Track ${fallbackIndex + 1}"
    }

    /** The track's own label, when it adds something to the language ("Commentary", "Signs & Songs"). */
    fun extraLabel(format: Format, language: String): String? =
        format.label?.trim()?.takeIf { it.isNotEmpty() && !it.equals(language, ignoreCase = true) }

    fun audioCodec(format: Format): String {
        val mime = format.sampleMimeType.orEmpty()
        return when {
            mime == MimeTypes.AUDIO_AAC -> "AAC"
            mime == MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            mime == MimeTypes.AUDIO_E_AC3 || mime == MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
            mime == MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            mime == MimeTypes.AUDIO_DTS || mime == MimeTypes.AUDIO_DTS_HD -> "DTS"
            mime == MimeTypes.AUDIO_OPUS -> "Opus"
            mime == MimeTypes.AUDIO_FLAC -> "FLAC"
            mime == MimeTypes.AUDIO_MPEG -> "MP3"
            mime == MimeTypes.AUDIO_VORBIS -> "Vorbis"
            mime == MimeTypes.AUDIO_ALAC -> "ALAC"
            else -> format.codecs?.takeIf { it.isNotBlank() }?.substringBefore('.')?.uppercase()
                ?: mime.substringAfter('/', "").uppercase().ifEmpty { null }
                ?: "Audio"
        }
    }

    fun channelLayout(channels: Int): String? = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        in 3..Int.MAX_VALUE -> "$channels channels"
        else -> null
    }

    /** Titles that repeat get a number from the second copy on: "English", "English 2", "English 3". */
    fun numbered(titles: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return titles.map { title ->
            val count = (seen[title] ?: 0) + 1
            seen[title] = count
            if (count == 1) title else "$title $count"
        }
    }

    fun unsupportedReason(format: MediaSubtitleFormat?, bitmap: Boolean): String = when {
        bitmap -> "Picture subtitles (${format?.label ?: "bitmap"}) can't be shown on this phone"
        else -> "This ${format?.label ?: "subtitle"} format isn't supported on this phone"
    }
}
