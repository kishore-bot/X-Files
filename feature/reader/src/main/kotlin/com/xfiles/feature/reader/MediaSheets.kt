package com.xfiles.feature.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.media3.exoplayer.ExoPlayer
import com.xfiles.core.documents.FileEntry

private val SUBTITLE_TYPES = arrayOf("application/x-subrip", "text/vtt", "text/x-ssa", "text/*", "application/octet-stream")

/** Shows the one sheet that is open, if any, and routes its choices to [onEvent]. */
@Composable
internal fun MediaSheetHost(
    state: MediaPlayerUiState,
    file: FileEntry,
    player: ExoPlayer,
    device: MediaDevice,
    onEvent: (MediaPlayerEvent) -> Unit,
) {
    val close: () -> Unit = { onEvent(MediaPlayerEvent.OpenSheet(null)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onEvent(MediaPlayerEvent.LoadSubtitle(uri))
    }
    when (state.sheet) {
        MediaSheet.Audio -> MediaAudioSheet(
            tracks = state.audioTracks,
            onChoose = { onEvent(MediaPlayerEvent.ChooseAudio(it)) },
            onDismiss = close,
        )
        MediaSheet.Subtitles -> MediaSubtitleSheet(
            state = state,
            onEvent = onEvent,
            onPickFile = { picker.launch(SUBTITLE_TYPES) },
            onDismiss = close,
        )
        MediaSheet.Playback -> MediaPlaybackSheet(
            speedIndex = state.speedIndex,
            device = device,
            onSpeed = { onEvent(MediaPlayerEvent.SetSpeed(it)) },
            onDismiss = close,
        )
        MediaSheet.Details -> MediaDetailsSheet(file, player, close)
        null -> Unit
    }
}
