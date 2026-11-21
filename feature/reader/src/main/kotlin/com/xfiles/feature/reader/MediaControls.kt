package com.xfiles.feature.reader

import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzIcons

/** The screen orientation the player asks for. [Auto] follows the system's rotation setting. */
internal enum class MediaOrientation(val label: String, val requested: Int) {
    Auto("Auto", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    Landscape("Landscape", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    Portrait("Portrait", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
}

/** Playback speeds the player cycles through. */
internal val MediaSpeedSteps: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Index of 1x in [MediaSpeedSteps]. */
internal const val MediaSpeedDefault = 2

internal fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "x"

/** Speed, orientation and Picture-in-Picture buttons, shown over the top-right of the picture. */
@Composable
internal fun VideoOverlayControls(
    speed: Float,
    orientation: MediaOrientation,
    onSpeed: () -> Unit,
    onOrientation: () -> Unit,
    onPictureInPicture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColors = ButtonDefaults.textButtonColors(contentColor = Color.White)
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSpeed, colors = textColors) { Text(speedLabel(speed)) }
        TextButton(onClick = onOrientation, colors = textColors) { Text(orientation.label) }
        IconButton(onClick = onPictureInPicture) {
            Icon(EzIcons.PictureInPicture, "Picture-in-picture", tint = Color.White)
        }
    }
}
