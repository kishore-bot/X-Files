package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzIcons

/**
 * Close, the title, the Audio and (for video) Subtitles actions, and the ⋮ menu for the rest, over a fade from black
 * at the top of the picture. Audio and CC are visible pills, not only menu rows.
 */
@Composable
internal fun MediaTopBar(
    title: String,
    subtitle: String?,
    video: Boolean,
    subtitlesOn: Boolean,
    scale: VideoScale,
    rotationLocked: Boolean,
    onClose: () -> Unit,
    onSheet: (MediaSheet) -> Unit,
    onScale: () -> Unit,
    onPictureInPicture: () -> Unit,
    onRotationLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(EzIcons.Close, "Close player", onClose)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TopActionPill(EzIcons.Audio, "Audio", active = false) { onSheet(MediaSheet.Audio) }
        if (video) {
            Box(Modifier.size(8.dp))
            TopActionPill(EzIcons.Subtitles, if (subtitlesOn) "CC on" else "CC", active = subtitlesOn) {
                onSheet(MediaSheet.Subtitles)
            }
        }
        Box(Modifier.size(8.dp))
        Box {
            RoundButton(EzIcons.More, "More options", { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = MaterialTheme.shapes.large) {
                fun pick(action: () -> Unit) {
                    menuOpen = false
                    action()
                }
                MenuRow("Playback", EzIcons.Music) { pick { onSheet(MediaSheet.Playback) } }
                if (video) MenuRow("Resize: ${scale.label}", EzIcons.AspectRatio) { pick(onScale) }
                if (video) MenuRow("Picture-in-picture", EzIcons.PictureInPicture) { pick(onPictureInPicture) }
                if (video) {
                    MenuRow(
                        if (rotationLocked) "Unlock rotation" else "Lock rotation",
                        if (rotationLocked) EzIcons.Lock else EzIcons.ScreenLockRotation,
                    ) { pick(onRotationLock) }
                }
                MenuRow("File details", EzIcons.Document) { pick { onSheet(MediaSheet.Details) } }
            }
        }
    }
}

/** A labelled pill in the top bar: white when [active], translucent otherwise. */
@Composable
private fun TopActionPill(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val haptics = rememberMediaHaptics()
    Row(
        Modifier
            .height(40.dp)
            .background(
                if (active) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.16f),
                RoundedCornerShape(20.dp),
            )
            .clickable(role = Role.Button, onClickLabel = label) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (active) Color.Black else Color.White
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
    )
}
