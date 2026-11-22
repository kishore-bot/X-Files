package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzIcons

/** The frosted play control laid over a slide's audio or video. */
@Composable
internal fun SlideMediaBadge(audio: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x33000000))))
            .border(1.dp, Color(0x80FFFFFF), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (audio) EzIcons.Music else EzIcons.Play,
            contentDescription = if (audio) "Play audio" else "Play video",
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}
