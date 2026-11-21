package com.xfiles.feature.reader

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class SubtitleSize(val label: String, val sp: Int) { Small("Small", 15), Medium("Medium", 19), Large("Large", 24) }

/** Solid only: a frosted box behind moving video smears the words. */
enum class SubtitleBackground(val label: String) { None("None"), Box("Box") }

enum class SubtitleLook(val label: String) { Outline("Outline"), Shadow("Shadow"), Plain("Plain") }

/** Auto picks black or white from the picture behind the words. */
enum class SubtitleColor(val label: String) { Auto("Auto"), White("White"), Yellow("Yellow") }

data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.Medium,
    val background: SubtitleBackground = SubtitleBackground.None,
    val look: SubtitleLook = SubtitleLook.Outline,
    val color: SubtitleColor = SubtitleColor.Auto,
)

/** Kept across videos and launches; a few bytes, read once per player. */
internal class SubtitleStyleStore(context: Context) {
    private val prefs = context.getSharedPreferences("subtitles", Context.MODE_PRIVATE)

    fun load() = SubtitleStyle(
        size = enumOr(prefs.getString("size", null), SubtitleSize.Medium),
        background = enumOr(prefs.getString("background", null), SubtitleBackground.None),
        look = enumOr(prefs.getString("look", null), SubtitleLook.Outline),
        color = enumOr(prefs.getString("color", null), SubtitleColor.Auto),
    )

    fun save(style: SubtitleStyle) = prefs.edit()
        .putString("size", style.size.name)
        .putString("background", style.background.name)
        .putString("look", style.look.name)
        .putString("color", style.color.name)
        .apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

/** The cues the player is showing right now. */
@Composable
internal fun rememberCues(player: Player): List<String> {
    var cues by remember(player) { mutableStateOf(emptyList<String>()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return cues
}

/**
 * True when the strip of picture behind the subtitles is bright. Samples a tiny frame near the current
 * position every couple of seconds, on one background lane, only while words are on screen.
 */
@Composable
internal fun rememberBrightBehindSubtitles(uri: Uri, player: Player, active: Boolean): Boolean {
    val context = LocalContext.current.applicationContext
    var bright by remember(uri) { mutableStateOf(false) }
    val retriever = remember(uri) { MediaMetadataRetriever() }
    DisposableEffect(retriever) { onDispose { retriever.release() } }
    LaunchedEffect(retriever, active) {
        if (!active) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                retriever.setDataSource(context, uri)
            } catch (error: Exception) {
                Log.w("XfilesSubtitles", "No frames for auto colour", error)
                return@withContext
            }
            while (isActive) {
                val at = withContext(Dispatchers.Main) { player.currentPosition } * 1000
                val frame = try {
                    retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 54)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    null
                }
                if (frame != null) {
                    bright = lowerStripLuma(frame) > 0.62f
                    frame.recycle()
                }
                delay(1_500)
            }
        }
    }
    return bright
}

/** Average brightness of the bottom quarter, where subtitles sit. */
private fun lowerStripLuma(frame: Bitmap): Float {
    val top = frame.height * 3 / 4
    val rows = frame.height - top
    if (rows <= 0 || frame.width <= 0) return 0f
    val pixels = IntArray(frame.width * rows)
    frame.getPixels(pixels, 0, frame.width, 0, top, frame.width, rows)
    var sum = 0L
    for (p in pixels) sum += (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
    return sum.toFloat() / (pixels.size * 255)
}

@Composable
internal fun SubtitleText(lines: List<String>, style: SubtitleStyle, brightBehind: Boolean, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val fill = when (style.color) {
        SubtitleColor.Auto -> if (brightBehind && style.background == SubtitleBackground.None) Color.Black else Color.White
        SubtitleColor.White -> Color.White
        SubtitleColor.Yellow -> Color(0xFFFFE14D)
    }
    // The edge is always the opposite of the fill, so words read on any picture.
    val edge = if (fill == Color.Black) Color.White else Color.Black
    val text = lines.joinToString("\n")
    val base = TextStyle(
        fontSize = style.size.sp.sp,
        lineHeight = (style.size.sp * 1.3f).sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val boxed = style.background == SubtitleBackground.Box
    Box(
        modifier
            .widthIn(max = 720.dp)
            .then(if (boxed) Modifier.background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            boxed || style.look == SubtitleLook.Plain -> Text(text, style = base, color = if (boxed && fill == Color.Black) Color.White else fill)
            style.look == SubtitleLook.Shadow -> Text(
                text,
                style = base.copy(shadow = Shadow(edge.copy(alpha = 0.9f), Offset(2f, 2f), blurRadius = 6f)),
                color = fill,
            )
            else -> {
                val stroke = with(LocalDensity.current) { 3.dp.toPx() }
                Text(text, style = base.copy(drawStyle = Stroke(width = stroke)), color = edge)
                Text(text, style = base, color = fill)
            }
        }
    }
}

/** One text track the player knows about: embedded in the video or loaded from a file. */
internal data class SubtitleTrack(val group: Tracks.Group, val label: String, val selected: Boolean)

internal fun Player.subtitleTracks(): List<SubtitleTrack> =
    currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }.mapIndexed { index, group ->
        val format = group.getTrackFormat(0)
        val label = format.label ?: format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Track ${index + 1}"
        SubtitleTrack(group, label, group.isSelected)
    }

internal fun Player.showSubtitles(track: SubtitleTrack?) {
    trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
        if (track == null) {
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, 0))
        }
    }.build()
}

internal fun subtitleMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "vtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleSheet(
    tracks: List<SubtitleTrack>,
    style: SubtitleStyle,
    onTrack: (SubtitleTrack?) -> Unit,
    onLoad: (Uri) -> Unit,
    onStyle: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onLoad(uri) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Subtitles", style = MaterialTheme.typography.titleLarge)
            Choice("Track") {
                FilterChip(tracks.none { it.selected }, onClick = { onTrack(null) }, label = { Text("Off") })
                tracks.forEach { track -> FilterChip(track.selected, onClick = { onTrack(track) }, label = { Text(track.label) }) }
            }
            TextButton(onClick = { picker.launch(arrayOf("application/x-subrip", "text/vtt", "text/*", "application/octet-stream")) }) {
                Text("Load a subtitle file (.srt, .vtt, .ass)…")
            }
            Choice("Size") {
                SubtitleSize.entries.forEach { FilterChip(style.size == it, onClick = { onStyle(style.copy(size = it)) }, label = { Text(it.label) }) }
            }
            Choice("Background") {
                SubtitleBackground.entries.forEach { FilterChip(style.background == it, onClick = { onStyle(style.copy(background = it)) }, label = { Text(it.label) }) }
            }
            Choice("Text style") {
                SubtitleLook.entries.forEach { FilterChip(style.look == it, onClick = { onStyle(style.copy(look = it)) }, label = { Text(it.label) }) }
            }
            Choice("Colour") {
                SubtitleColor.entries.forEach { FilterChip(style.color == it, onClick = { onStyle(style.copy(color = it)) }, label = { Text(it.label) }) }
            }
            Box(Modifier.fillMaxWidth().background(Color(0xFF2B2B2B), RoundedCornerShape(12.dp)).padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                SubtitleText(listOf("This is how subtitles look."), style, brightBehind = false)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}
