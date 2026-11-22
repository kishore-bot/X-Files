package com.xfiles.feature.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import com.xfiles.core.documents.FileEntry
import com.xfiles.feature.reader.domain.MediaAudioTrack
import com.xfiles.feature.reader.domain.MediaMetadataUseCase
import com.xfiles.feature.reader.domain.MediaSubtitleFile
import com.xfiles.feature.reader.domain.MediaTextTrack
import com.xfiles.feature.reader.domain.MediaSubtitleFilesUseCase
import com.xfiles.feature.reader.domain.MediaTracksUseCase
import com.xfiles.feature.reader.domain.subtitleMimeFor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The player's state machine. The screen attaches the ExoPlayer it owns while it is on screen and detaches it
 * when the view goes away; state survives rotation, and nothing here holds the player after detach.
 */
@HiltViewModel
internal class MediaPlayerViewModel @Inject constructor(
    private val tracks: MediaTracksUseCase,
    private val subtitleFiles: MediaSubtitleFilesUseCase,
    private val metadata: MediaMetadataUseCase,
    private val styleStore: SubtitleStyleStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MediaPlayerUiState(subtitleStyle = styleStore.load()))
    val state: StateFlow<MediaPlayerUiState> = mutableState.asStateFlow()

    private var player: ExoPlayer? = null
    private var media: FileEntry? = null
    private var nearbyFiles: List<MediaSubtitleFile> = emptyList()
    private val loadedFiles = mutableListOf<MediaSubtitleFile>()
    private var resumeAfterScrub = false
    private var pollJob: Job? = null
    private var hideJob: Job? = null
    private var cueJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            readPlayback(player)
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) refreshTracks(player)
        }

        override fun onCues(cueGroup: CueGroup) {
            showCues(cueGroup.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) })
        }
    }

    /** Binds the screen's player to this ViewModel. Calling it again with the same player does nothing. */
    fun attach(exo: ExoPlayer, file: FileEntry, video: Boolean) {
        if (player === exo) return
        detach()
        player = exo
        media = file
        loadedFiles.clear()
        nearbyFiles = emptyList()
        mutableState.update { previous ->
            MediaPlayerUiState(
                video = video,
                title = file.name.substringBeforeLast('.'),
                attached = true,
                speedIndex = previous.speedIndex,
                scale = previous.scale,
                rotationLocked = previous.rotationLocked,
                subtitleStyle = previous.subtitleStyle,
            )
        }
        exo.addListener(listener)
        exo.setPlaybackSpeed(MediaSpeedSteps[state.value.speedIndex])
        refreshTracks(exo)
        readPlayback(exo)
        startPolling()
        scheduleHide()
        if (!video) {
            viewModelScope.launch {
                val info = metadata(file.uri)
                if (media?.uri != file.uri) return@launch
                mutableState.update {
                    it.copy(
                        title = info.title ?: it.title,
                        artist = info.artist,
                        album = info.album,
                        artwork = info.artwork,
                    )
                }
            }
        } else {
            viewModelScope.launch {
                val found = subtitleFiles.nearby(file.uri)
                if (media?.uri != file.uri) return@launch
                nearbyFiles = found
                refreshTracks(exo)
            }
        }
    }

    /** Unbinds the player; the state stays for the next attach. */
    fun detach() {
        val exo = player ?: return
        exo.removeListener(listener)
        pollJob?.cancel()
        hideJob?.cancel()
        cueJob?.cancel()
        player = null
        media = null
        mutableState.update {
            it.copy(attached = false, playing = false, cues = emptyList(), scrubFraction = null, dragging = false, sheet = null)
        }
    }

    fun onEvent(event: MediaPlayerEvent) {
        val exo = player
        when (event) {
            MediaPlayerEvent.PlayPause -> exo?.togglePlayback()
            MediaPlayerEvent.SeekBack -> exo?.seekBack()
            MediaPlayerEvent.SeekForward -> exo?.seekForward()
            is MediaPlayerEvent.SeekTo -> exo?.seekTo(event.positionMs)
            is MediaPlayerEvent.Scrub -> scrub(exo, event.fraction)
            MediaPlayerEvent.ScrubFinished -> finishScrub(exo)
            is MediaPlayerEvent.SetDragging -> mutableState.update { it.copy(dragging = event.active) }
            is MediaPlayerEvent.SetSpeed -> {
                mutableState.update { it.copy(speedIndex = event.index) }
                exo?.setPlaybackSpeed(MediaSpeedSteps[event.index])
            }
            is MediaPlayerEvent.Boost -> {
                mutableState.update { it.copy(boosted = event.on) }
                exo?.setPlaybackSpeed(if (event.on) BOOST_SPEED else MediaSpeedSteps[state.value.speedIndex])
            }
            MediaPlayerEvent.ToggleRepeat -> {
                val on = !state.value.repeatOne
                mutableState.update { it.copy(repeatOne = on) }
                exo?.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
            MediaPlayerEvent.CycleScale -> mutableState.update { it.copy(scale = it.scale.next()) }
            MediaPlayerEvent.ToggleRotationLock -> mutableState.update { it.copy(rotationLocked = !it.rotationLocked) }
            MediaPlayerEvent.ToggleControls -> if (state.value.controlsVisible) hideControls() else showControls()
            MediaPlayerEvent.TouchControls -> showControls()
            is MediaPlayerEvent.OpenSheet -> mutableState.update {
                it.copy(
                    sheet = event.sheet,
                    subtitleLevel = if (event.sheet == MediaSheet.Subtitles) SubtitleLevel.Tracks else it.subtitleLevel,
                    subtitleNotice = null,
                )
            }
            is MediaPlayerEvent.SetSubtitleLevel -> mutableState.update { it.copy(subtitleLevel = event.level) }
            is MediaPlayerEvent.ChooseAudio -> {
                chooseAudio(exo, event.track)
                mutableState.update { it.copy(sheet = null) }
            }
            is MediaPlayerEvent.ChooseSubtitle -> chooseSubtitle(exo, event.track)
            is MediaPlayerEvent.ChooseNearbySubtitle -> addSubtitle(event.file)
            is MediaPlayerEvent.LoadSubtitle -> loadSubtitle(event.uri)
            is MediaPlayerEvent.UpdateStyle -> {
                mutableState.update { it.copy(subtitleStyle = event.style) }
                styleStore.save(event.style)
            }
            is MediaPlayerEvent.SubtitleDelay -> mutableState.update {
                it.copy(subtitleDelayMs = (it.subtitleDelayMs + event.deltaMs).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS))
            }
        }
        if (event !is MediaPlayerEvent.SetDragging && event !is MediaPlayerEvent.Scrub && event !is MediaPlayerEvent.Boost) {
            showControls()
        }
    }

    private fun readPlayback(exo: Player) {
        val before = state.value.playing
        val playing = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED
        mutableState.update {
            it.copy(
                attached = true,
                playerState = exo.playbackState,
                playing = playing,
                position = exo.currentPosition.coerceAtLeast(0L),
                duration = exo.duration.coerceAtLeast(0L),
                seekable = exo.isCurrentMediaItemSeekable,
                failed = exo.playerError != null,
                aspect = exo.videoSize.displayAspect(),
            )
        }
        if (playing && !before) scheduleHide()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                player?.let { exo -> if (state.value.playing) readPlayback(exo) }
                delay(POLL_MS)
            }
        }
    }

    private fun scrub(exo: ExoPlayer?, fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (state.value.scrubFraction == null && exo != null) {
            resumeAfterScrub = exo.playWhenReady
            exo.pause()
            exo.isScrubbingModeEnabled = true
        }
        mutableState.update { it.copy(scrubFraction = clamped) }
        val duration = state.value.duration
        if (exo != null && state.value.video && duration > 0L) exo.seekTo((clamped * duration).toLong())
    }

    private fun finishScrub(exo: ExoPlayer?) {
        val fraction = state.value.scrubFraction ?: return
        if (exo != null) {
            exo.seekTo((fraction * state.value.duration).toLong())
            exo.isScrubbingModeEnabled = false
            if (resumeAfterScrub) exo.play()
            readPlayback(exo)
        }
        mutableState.update { it.copy(scrubFraction = null) }
    }

    private fun refreshTracks(exo: Player) {
        val names = loadedFiles.map { it.label() }.toSet()
        val set = tracks(exo.currentTracks, names)
        val loadedUris = loadedFiles.map { it.uri }.toSet()
        mutableState.update {
            it.copy(
                audioTracks = set.audio,
                textTracks = set.text,
                nearbySubtitles = nearbyFiles.filter { file -> file.uri !in loadedUris },
            )
        }
    }

    private fun chooseAudio(exo: ExoPlayer?, track: MediaAudioTrack) {
        if (exo == null) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, 0))
            .build()
    }

    private fun chooseSubtitle(exo: ExoPlayer?, track: MediaTextTrack?) {
        exo ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().apply {
            if (track == null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, 0))
            }
        }.build()
        mutableState.update { it.copy(subtitleNotice = null) }
    }

    private fun loadSubtitle(uri: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(subtitleLoading = true) }
            val name = subtitleFiles.displayName(uri)
            addSubtitle(MediaSubtitleFile(uri, name))
            mutableState.update { it.copy(subtitleLoading = false) }
        }
    }

    /** Adds a subtitle file to the playing video, keeping its place, and turns it on. */
    private fun addSubtitle(file: MediaSubtitleFile) {
        val exo = player ?: return
        val source = media ?: return
        if (subtitleMimeFor(file.name) == null) {
            mutableState.update {
                it.copy(subtitleNotice = "Only .srt, .vtt and .ass subtitle files can be loaded.", subtitleLoading = false)
            }
            return
        }
        if (loadedFiles.none { it.uri == file.uri }) loadedFiles += file
        val configurations = loadedFiles.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(sub.uri)
                .setMimeType(subtitleMimeFor(sub.name) ?: MimeTypes.APPLICATION_SUBRIP)
                .setLabel(sub.label())
                .setSelectionFlags(if (sub.uri == file.uri) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val position = exo.currentPosition
        val playing = exo.playWhenReady
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        exo.setMediaItem(MediaItem.Builder().setUri(source.uri).setSubtitleConfigurations(configurations).build(), position)
        exo.playWhenReady = playing
        exo.prepare()
        mutableState.update { it.copy(subtitleNotice = null) }
    }

    private fun showCues(lines: List<String>) {
        cueJob?.cancel()
        val delay = state.value.subtitleDelayMs
        // A later subtitle waits for the delay; an earlier one can't be shown before the player delivers it.
        if (delay <= 0L) {
            mutableState.update { it.copy(cues = lines) }
            return
        }
        cueJob = viewModelScope.launch {
            delay(delay)
            mutableState.update { it.copy(cues = lines) }
        }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = viewModelScope.launch {
            delay(CONTROLS_HIDE_MS)
            val now = state.value
            if (now.video && now.playing && now.scrubFraction == null && !now.dragging && now.sheet == null) {
                mutableState.update { it.copy(controlsVisible = false) }
            }
        }
    }

    private fun showControls() {
        mutableState.update { it.copy(controlsVisible = true) }
        scheduleHide()
    }

    private fun hideControls() {
        hideJob?.cancel()
        mutableState.update { it.copy(controlsVisible = false) }
    }

    private fun MediaSubtitleFile.label(): String = name.substringBeforeLast('.')

    private companion object {
        /** How long the controls stay after the last touch while a video plays. */
        const val CONTROLS_HIDE_MS = 3_000L
        const val POLL_MS = 250L
        const val MAX_DELAY_MS = 5_000L
        /** Speed while a long press holds the picture. */
        const val BOOST_SPEED = 2f
    }
}

private fun VideoSize.displayAspect(): Float =
    if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else 0f
