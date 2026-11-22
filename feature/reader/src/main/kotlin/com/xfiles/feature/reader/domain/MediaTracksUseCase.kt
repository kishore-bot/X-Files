package com.xfiles.feature.reader.domain

import androidx.media3.common.C
import androidx.media3.common.Tracks
import javax.inject.Inject

/**
 * Turns the player's raw track groups into the audio and subtitle rows the sheets show. Unsupported tracks stay in
 * the list, marked with a reason, so the user can see why a language can't be chosen.
 */
class MediaTracksUseCase @Inject constructor() {

    /** [loadedNames] are the labels of subtitle files the user loaded; they are marked "File". */
    operator fun invoke(tracks: Tracks, loadedNames: Set<String>): MediaTrackSet {
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        val audioBase = audioGroups.mapIndexed { index, group ->
            val format = group.getTrackFormat(0)
            val language = MediaTrackFormatting.languageName(format, index)
            val title = listOfNotNull(language, MediaTrackFormatting.extraLabel(format, language)).joinToString(" · ")
            val detail = listOfNotNull(
                MediaTrackFormatting.audioCodec(format),
                MediaTrackFormatting.channelLayout(format.channelCount),
            ).joinToString(" · ")
            Triple(title, detail, group)
        }
        val audioTitles = MediaTrackFormatting.numbered(audioBase.map { it.first })
        val audio = audioBase.mapIndexed { index, (_, detail, group) ->
            MediaAudioTrack(
                id = "audio-$index",
                title = audioTitles[index],
                detail = detail,
                selected = group.isSelected,
                supported = group.isSupported,
                reason = if (group.isSupported) null else "This audio format isn't supported on this phone",
                group = group,
            )
        }

        val textBase = textGroups.mapIndexed { index, group ->
            val format = group.getTrackFormat(0)
            val language = MediaTrackFormatting.languageName(format, index)
            val title = listOfNotNull(language, MediaTrackFormatting.extraLabel(format, language)).joinToString(" · ")
            Pair(title, group)
        }
        val textTitles = MediaTrackFormatting.numbered(textBase.map { it.first })
        val text = textBase.mapIndexed { index, (_, group) ->
            val format = group.getTrackFormat(0)
            val kind = MediaTrackFormatting.subtitleFormat(format)
            val flags = buildList {
                if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("Forced")
                if (format.roleFlags and C.ROLE_FLAG_CAPTION != 0 || looksSdh(format.label)) add("SDH")
                if (format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0) add("Default")
                if (format.label in loadedNames) add("File")
            }
            MediaTextTrack(
                id = "text-$index",
                title = textTitles[index],
                format = kind,
                flags = flags,
                selected = group.isSelected,
                supported = group.isSupported,
                reason = if (group.isSupported) null else MediaTrackFormatting.unsupportedReason(kind, kind.bitmap),
                group = group,
            )
        }
        return MediaTrackSet(audio, text)
    }

    private fun looksSdh(label: String?): Boolean =
        label != null && Regex("""\b(SDH|HI|CC)\b""", RegexOption.IGNORE_CASE).containsMatchIn(label)
}
