package com.xfiles.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.feature.reader.domain.MediaAudioTrack

/**
 * The Audio language sheet: every audio track in the file with its language, codec and channel layout. The track
 * that plays has a check; a track the phone can't decode says why and is greyed out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaAudioSheet(
    tracks: List<MediaAudioTrack>,
    onChoose: (MediaAudioTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Audio language", style = MaterialTheme.typography.titleLarge)
            if (tracks.isEmpty()) {
                Text("No selectable audio tracks are available.", color = EzColors.TextSecondary, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                Text(
                    if (tracks.size == 1) "1 track in this file" else "${tracks.size} tracks in this file",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                )
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                tracks.take(64).forEach { track ->
                    MediaChoiceRow(
                        title = track.title,
                        detail = track.reason ?: track.detail.ifBlank { null },
                        selected = track.selected,
                        enabled = track.supported,
                        warning = track.reason != null,
                        cue = { it.confirm() },
                        onClick = { onChoose(track) },
                    )
                }
            }
        }
    }
}
