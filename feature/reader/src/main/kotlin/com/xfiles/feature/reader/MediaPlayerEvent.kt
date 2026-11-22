package com.xfiles.feature.reader

import android.net.Uri
import com.xfiles.feature.reader.domain.MediaAudioTrack
import com.xfiles.feature.reader.domain.MediaSubtitleFile
import com.xfiles.feature.reader.domain.MediaTextTrack

/** Everything the player screen can ask the ViewModel to do. */
internal sealed interface MediaPlayerEvent {
    data object PlayPause : MediaPlayerEvent
    data object SeekBack : MediaPlayerEvent
    data object SeekForward : MediaPlayerEvent
    data class SeekTo(val positionMs: Long) : MediaPlayerEvent

    /** A slider or a finger is moving to [fraction] of the duration. */
    data class Scrub(val fraction: Float) : MediaPlayerEvent
    data object ScrubFinished : MediaPlayerEvent

    /** A drag over the picture is running (true) or ended (false). */
    data class SetDragging(val active: Boolean) : MediaPlayerEvent

    data class SetSpeed(val index: Int) : MediaPlayerEvent

    /** Long press: double speed while held. */
    data class Boost(val on: Boolean) : MediaPlayerEvent
    data object ToggleRepeat : MediaPlayerEvent
    data object CycleScale : MediaPlayerEvent
    data object ToggleRotationLock : MediaPlayerEvent
    data object ToggleControls : MediaPlayerEvent

    /** Any touch on the controls: keeps them up for a while. */
    data object TouchControls : MediaPlayerEvent

    /** Opens [sheet], or closes it when null. */
    data class OpenSheet(val sheet: MediaSheet?) : MediaPlayerEvent
    data class SetSubtitleLevel(val level: SubtitleLevel) : MediaPlayerEvent
    data class ChooseAudio(val track: MediaAudioTrack) : MediaPlayerEvent

    /** Shows [track], or no subtitles when null. */
    data class ChooseSubtitle(val track: MediaTextTrack?) : MediaPlayerEvent
    data class ChooseNearbySubtitle(val file: MediaSubtitleFile) : MediaPlayerEvent
    data class LoadSubtitle(val uri: Uri) : MediaPlayerEvent
    data class UpdateStyle(val style: SubtitleStyle) : MediaPlayerEvent

    /** Moves the subtitle timing by [deltaMs] (clamped to plus or minus 5 s). */
    data class SubtitleDelay(val deltaMs: Long) : MediaPlayerEvent
}
