package com.xfiles.feature.reader

import android.graphics.Bitmap
import androidx.media3.common.Player
import com.xfiles.feature.reader.domain.MediaAudioTrack
import com.xfiles.feature.reader.domain.MediaSubtitleFile
import com.xfiles.feature.reader.domain.MediaTextTrack

/** The sheet opened from the player's bars. */
internal enum class MediaSheet { Audio, Subtitles, Playback, Details }

/** The subtitle sheet's two levels: the track list, then the style. */
internal enum class SubtitleLevel { Tracks, Style }

/**
 * Everything the player screen shows. Immutable: the ViewModel publishes a new copy for every change, and the
 * composables read it and nothing else.
 */
internal data class MediaPlayerUiState(
    val video: Boolean = false,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val artwork: Bitmap? = null,
    val attached: Boolean = false,
    val playerState: Int = Player.STATE_IDLE,
    /** Playing right now, or set to play and still buffering. Ended videos read as not playing. */
    val playing: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val seekable: Boolean = false,
    val failed: Boolean = false,
    /** Width over height of the picture, 0 when there is no picture yet. */
    val aspect: Float = 0f,
    val speedIndex: Int = MediaSpeedDefault,
    /** True while a long press runs the picture at double speed. */
    val boosted: Boolean = false,
    val repeatOne: Boolean = false,
    val scale: VideoScale = VideoScale.Fit,
    val rotationLocked: Boolean = false,
    val controlsVisible: Boolean = true,
    /** True while a one-finger gesture over the picture is running; the controls then stay up. */
    val dragging: Boolean = false,
    /** Where a slider drag points, 0..1; null when nobody is scrubbing. */
    val scrubFraction: Float? = null,
    val sheet: MediaSheet? = null,
    val subtitleLevel: SubtitleLevel = SubtitleLevel.Tracks,
    val audioTracks: List<MediaAudioTrack> = emptyList(),
    val textTracks: List<MediaTextTrack> = emptyList(),
    /** Subtitle files in the video's folder that aren't loaded yet. */
    val nearbySubtitles: List<MediaSubtitleFile> = emptyList(),
    val subtitleLoading: Boolean = false,
    /** Set when a subtitle file couldn't be loaded; cleared by the next subtitle action. */
    val subtitleNotice: String? = null,
    val subtitleDelayMs: Long = 0L,
    val cues: List<String> = emptyList(),
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
) {
    /** Some subtitle track is showing. */
    val subtitlesOn: Boolean get() = textTracks.any { it.selected }

    /** The line under the title in the top bar: the audio language and codec being played. */
    val audioSummary: String?
        get() = audioTracks.firstOrNull { it.selected }?.let { track ->
            listOfNotNull(track.title, track.detail.takeIf { it.isNotBlank() }).joinToString(" · ")
        }
}
