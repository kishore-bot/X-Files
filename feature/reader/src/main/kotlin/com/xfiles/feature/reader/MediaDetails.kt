@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xfiles.feature.reader

import com.xfiles.documents.formatPlaybackTime
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import com.xfiles.design.EzControlSheet
import com.xfiles.core.documents.FileEntry
import com.xfiles.design.EzColors
import java.text.DateFormat
import java.util.Date

/**
 * The facts of a playing media file: its name, type, size, modified date and folder from the file's record, and
 * its resolution, duration and codecs from the player's tracks. Rows the platform does not give are left out.
 */
@Composable
internal fun MediaDetailsSheet(
    file: FileEntry, player: Player, state: MediaPlayerUiState, output: MediaOutput, hdrDisplay: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val rows = buildList {
        add("Name" to file.name)
        add("Type" to file.name.substringAfterLast('.', "").uppercase().ifEmpty { "Unknown" })
        file.sizeBytes?.let { add("Size" to Formatter.formatFileSize(context, it)) }
        file.modifiedAt?.let { add("Modified" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
        add("Location" to locationOf(file.uri))
        add("Duration" to formatPlaybackTime(player.duration.coerceAtLeast(0L)))
        val size = player.videoSize
        if (size.width > 0 && size.height > 0) add("Resolution" to "${size.width} × ${size.height}")
        codecsOf(player).takeIf { it.isNotEmpty() }?.let { add("Selected codecs" to it.joinToString(", ")) }
        if (state.video) {
            add("Source color" to if (output.hasHdr10PlusMetadata) "HDR10+ metadata detected" else state.color.label)
            hdrDisplay?.let { add("Display capabilities" to it) }
            if (state.color.hdr || output.hasHdr10PlusMetadata) add("HDR output" to "Unverified")
        }
        output.videoDecoder?.let { add("Video decoder" to it) }
        output.audioDecoder?.let { add("Audio decoder" to it) }
        add("FFmpeg" to (output.ffmpegVersion ?: "Unavailable in this install"))
        output.route?.let { add("System media route" to it) }
        output.usbLosslessStatus?.let { add("USB lossless" to it) }
        output.outputFormat?.let { add("AudioTrack format" to it) }
        if (state.audioTracks.isNotEmpty()) add("Output quality" to "Unverified")
        output.spatial?.let { add("Spatial audio" to it) }
        if (output.headTrackingAvailable) add("Head tracking" to "Available")
        state.playbackError?.let { add("Playback error" to it) }
        state.audioTracks.forEach { track ->
            val source = track.group.getTrackFormat(0)
            val description = listOfNotNull(
                track.detail.takeIf { it.isNotBlank() },
                source.sampleRate.takeIf { it > 0 }?.let { "$it Hz source" },
                pcmLabel(source.losslessPcmEncoding() ?: source.pcmEncoding),
                if (source.sampleMimeType in losslessSources) "Lossless source" else null,
                if (track.selected) "Selected" else null,
                track.reason,
            )
            add(track.title to description.joinToString(" · "))
        }
    }
    EzControlSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("File details", style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(label, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The folder a document-provider URI sits in ("Download" for "primary:Download/clip.mp4"), or "Unknown". */
private fun locationOf(uri: Uri): String {
    val path = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfter(':', "")
    return path.substringBeforeLast('/', "").trim('/').ifEmpty { "Unknown" }
}

/** The codecs of the tracks being played (video, audio, text), in track order, without repeats. */
private fun codecsOf(player: Player): List<String> {
    val out = LinkedHashSet<String>()
    for (group in player.currentTracks.groups) {
        for (index in 0 until group.length) {
            if (!group.isTrackSelected(index)) continue
            val format = group.getTrackFormat(index)
            (format.codecs ?: format.sampleMimeType)?.takeIf { it.isNotBlank() }?.let(out::add)
        }
    }
    return out.toList()
}


private val losslessSources = setOf(MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_TRUEHD)
