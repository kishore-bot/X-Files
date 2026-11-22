package com.xfiles.feature.reader

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.xfiles.feature.reader.domain.SlideMedia
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import com.xfiles.design.EzIcons
import com.xfiles.design.rememberHaptics

/**
 * Plays one slide's media full screen: a Media3 player over a black backdrop. Video shows its frame;
 * audio shows a glass card. Both take a tap to pause or resume. The player is released on close.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun SlideMediaPlayer(media: SlideMedia, onClose: () -> Unit) {
    val haptics = rememberHaptics()
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(media) { ExoPlayer.Builder(context).build() }
    var playing by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, media) {
        val uri: Uri = when (media) {
            is SlideMedia.Stream -> media.url.toUri()
            is SlideMedia.Local -> Uri.fromFile(media.file)
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(role = Role.Button) {
                    haptics.tick()
                    if (player.isPlaying) player.pause() else player.play()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (media.audio) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0x33FFFFFF))
                        .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(28.dp))
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(EzIcons.Music, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                    Text(if (playing) "Playing" else "Paused", color = Color.White)
                }
            } else {
                ContentFrame(player = player, modifier = Modifier.fillMaxSize())
            }
            IconButton(
                onClick = { haptics.confirm(); onClose() },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(EzIcons.Close, contentDescription = "Close media", tint = Color.White)
            }
            Icon(
                if (playing) EzIcons.Pause else EzIcons.Play,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (playing) 0f else 0.9f),
                modifier = Modifier.size(64.dp).background(Color(0x55000000), CircleShape).padding(14.dp),
            )
        }
    }
}
