package com.xfiles.feature.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzOptionMenu
import com.xfiles.design.EzTextButton
import com.xfiles.design.FilyMascot
import com.xfiles.design.FilyMascotState
import com.xfiles.design.GlassIconButton
import com.xfiles.design.LocalReducedMotion
import com.xfiles.design.glassPanel
import com.xfiles.fily.FilySession
import com.xfiles.fily.SpeechLanguageSupport
import kotlinx.coroutines.delay
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.launch

/**
 * Fily as EzFiles shows it: no dialog and no scrim, so the app stays usable underneath. The robot flies
 * up from the bottom onto a glowing horizon, a glass card at the top carries the conversation, and a
 * status pill always says what Fily is doing. Fily listens by itself, answers aloud, then listens again,
 * and pauses after about a minute of silence. Tapping the robot pauses, resumes or interrupts it.
 */
@Composable
fun VoiceAssistantSheet(viewModel: FilyViewModel, session: FilySession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val enabled by viewModel.access.enabled.collectAsStateWithLifecycle()
    val contents by viewModel.access.contentAccess.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pickedLanguage by viewModel.speechLanguage.collectAsStateWithLifecycle()
    val languages by viewModel.speechLanguages.collectAsStateWithLifecycle()

    var partial by remember { mutableStateOf("") }
    var quietRestarts by remember { mutableIntStateOf(0) }
    // Whether Fily should be listening between turns; false once paused by a tap or by silence.
    var wantsToListen by remember { mutableStateOf(true) }
    // Between a reply arriving and the voice starting, so the mic doesn't open and hear Fily itself.
    var replyPending by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }

    val recognizer = rememberSpeechRecognition(
        hints = session::speechHints,
        preferredLanguage = { viewModel.speechLanguage.value },
    ) { result ->
        partial = result.text
        if (result.isFinal && result.text.isNotBlank()) {
            quietRestarts = 0
            partial = ""
            viewModel.ask(session, result.alternatives)
        }
    }
    val recognition by recognizer.state.collectAsStateWithLifecycle()
    val level = recognizer.level.collectAsStateWithLifecycle()
    val pack by recognizer.pack.collectAsStateWithLifecycle()
    val packBusy = pack is SpeechPack.Downloading || pack is SpeechPack.Scheduled
    fun downloadPack(tag: String? = null) {
        // Paused while the pack downloads, so the loop doesn't reopen the mic into a missing language.
        wantsToListen = false
        recognizer.downloadLanguage(tag)
    }
    // A finished pack: refresh the language list and start listening in it.
    LaunchedEffect(pack) {
        if (pack == SpeechPack.Ready) {
            viewModel.refreshSpeechLanguages()
            quietRestarts = 0
            wantsToListen = true
            delay(PACK_RESULT_MS)
            recognizer.packSeen()
        }
    }
    val speaker = rememberTextToSpeech()
    val speaking by speaker.state.collectAsStateWithLifecycle()

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        wantsToListen = granted
        if (granted) recognizer.start()
    }
    fun startMic() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recognizer.start()
        } else {
            wantsToListen = false
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // The reply is spoken in the picked language when the phone has a voice for it.
    LaunchedEffect(ui.reply) {
        val reply = ui.reply ?: return@LaunchedEffect
        replyPending = true
        speaker.speak(reply, viewModel.speechLanguage.value)
        delay(SPEECH_START_TIMEOUT_MS)
        replyPending = false
    }
    LaunchedEffect(speaking) { if (speaking == TtsState.Speaking) replyPending = false }

    // Continuous listening: the recognizer ends on silence or a timeout; Fily opens the mic again by
    // itself after each turn, and pauses after MAX_QUIET_RESTARTS silent sessions.
    LaunchedEffect(recognition, ui.working, speaking, replyPending, wantsToListen, enabled, packBusy) {
        if (!enabled || !wantsToListen || ui.working || replyPending || speaking == TtsState.Speaking || packBusy) return@LaunchedEffect
        if (recognition == SpeechState.Listening || recognition == SpeechState.NeedsLanguage) return@LaunchedEffect
        delay(RESTART_DELAY_MS)
        if (quietRestarts >= MAX_QUIET_RESTARTS) {
            quietRestarts = 0
            partial = ""
            wantsToListen = false
            return@LaunchedEffect
        }
        quietRestarts++
        startMic()
    }
    LaunchedEffect(Unit) { viewModel.refreshSpeechLanguages() }

    // Fly-in: the robot rises from below on a soft spring and the horizon opens out under it.
    val arrival = remember { Animatable(if (reduced) 1f else 0f) }
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        if (!reduced) arrival.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { clock.floatValue = (it - start) / 1_000_000_000f }
    }
    var leaving by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!leaving) {
            leaving = true
            recognizer.cancel()
            speaker.stop()
            viewModel.stop()
            scope.launch {
                if (!reduced) arrival.animateTo(0f, tween(260))
                onDismiss()
            }
        }
    }
    BackHandler(onBack = close)
    val haptics = com.xfiles.design.rememberHaptics()

    fun tapRobot() {
        when {
            speaking == TtsState.Speaking || replyPending -> {
                speaker.stop()
                replyPending = false
                quietRestarts = 0
                wantsToListen = true
            }
            ui.working -> Unit
            recognition == SpeechState.Listening -> {
                recognizer.cancel()
                partial = ""
                wantsToListen = false
            }
            else -> {
                micDenied = false
                quietRestarts = 0
                wantsToListen = true
                startMic()
            }
        }
    }

    val mascotState = when {
        ui.working -> FilyMascotState.Thinking
        speaking == TtsState.Speaking -> FilyMascotState.Speaking
        replyPending -> FilyMascotState.Happy
        recognition == SpeechState.Listening -> FilyMascotState.Listening
        else -> FilyMascotState.Idle
    }
    val needsLanguage = recognition == SpeechState.NeedsLanguage
    val engineMissing = recognition == SpeechState.Error && recognizer.engineMissing
    val (status, dot) = when {
        !enabled -> "Fily is off" to EzColors.TextSecondary
        micDenied -> "Microphone not allowed · tap Fily to retry" to Color(0xFFE5484D)
        pack is SpeechPack.Downloading -> ((pack as SpeechPack.Downloading).percent?.let { "Downloading speech pack · $it%" } ?: "Downloading speech pack…") to Color(0xFF3E63DD)
        pack == SpeechPack.Scheduled -> "Speech pack queued · waiting for Wi-Fi" to Color(0xFFF5A524)
        pack == SpeechPack.Ready -> "Speech pack ready" to Color(0xFF30A46C)
        pack == SpeechPack.Failed -> "Speech pack download failed" to Color(0xFFE5484D)
        needsLanguage -> "Speech pack needed" to Color(0xFFF5A524)
        engineMissing -> "On-device speech recognition needed" to Color(0xFFF5A524)
        recognition == SpeechState.Error -> "Speech recognition unavailable" to Color(0xFFE5484D)
        ui.working -> "Thinking…" to Color(0xFF3E63DD)
        speaking == TtsState.Error -> "Offline speech playback unavailable" to Color(0xFFE5484D)
        speaking == TtsState.Speaking -> "Speaking · tap to interrupt" to Color(0xFF8E4EC6)
        recognition == SpeechState.Listening -> "Listening · speak now" to Color(0xFF30A46C)
        wantsToListen -> "Starting the mic…" to Color(0xFFF5A524)
        else -> "Paused · tap Fily to talk" to EzColors.TextSecondary
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // The conversation card, at the top.
        val question = if (needsLanguage) "" else partial.ifBlank { ui.request.orEmpty() }
        val answer = when {
            !enabled -> "Fily is switched off. Turn it on in Fily's settings."
            packBusy -> "Fetching the offline speech pack from Google. Fily starts listening as soon as it's ready."
            pack == SpeechPack.Failed -> "The speech pack didn't download. Check your connection and try again."
            needsLanguage -> "To hear you offline, your phone needs a speech pack for this language. Download it, then tap Fily."
            engineMissing -> "Fily needs an on-device speech recognizer. On Android 12 or later, install or enable a compatible speech service, then tap Fily."
            ui.working -> "Thinking…"
            ui.error != null -> ui.error.orEmpty()
            else -> ui.reply.orEmpty()
        }
        AnimatedVisibility(
            visible = !leaving && (question.isNotBlank() || answer.isNotBlank() || settingsOpen),
            enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { -it / 3 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (settingsOpen) {
                    FilySettings(
                        enabled = enabled,
                        contents = contents,
                        languages = languages,
                        pickedLanguage = pickedLanguage,
                        onEnabled = {
                            if (!it) {
                                recognizer.cancel()
                                speaker.stop()
                                session.clear()
                            }
                            viewModel.setEnabled(it)
                        },
                        onContents = {
                            if (!it) {
                                session.clear()
                                speaker.stop()
                            }
                            viewModel.setContentAccess(it)
                        },
                        onLanguage = { tag ->
                            viewModel.selectSpeechLanguage(tag)
                            if (languages.downloadable.any { it.tag == tag }) {
                                settingsOpen = false
                                downloadPack(tag)
                            }
                        },
                    )
                } else {
                    if (question.isNotBlank()) {
                        Text(question, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (answer.isNotBlank()) {
                        Text(
                            answer,
                            color = if (ui.error != null && !ui.working) MaterialTheme.colorScheme.error else EzColors.Text,
                            style = MaterialTheme.typography.titleMedium.copy(lineHeight = 24.sp),
                        )
                    }
                    val downloading = pack as? SpeechPack.Downloading
                    when {
                        downloading != null -> {
                            val percent = downloading.percent
                            if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                        pack == SpeechPack.Failed -> EzTextButton("Try again", { downloadPack() })
                        needsLanguage -> EzTextButton("Download speech pack", { downloadPack() })
                        engineMissing -> EzTextButton("Get Google speech", recognizer::getSpeechEngine)
                    }
                }
                Text("Fily · On-device", color = EzColors.TextSecondary.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }

        // The stage and the robot, above the bottom bar; only the robot takes taps.
        val rise = with(LocalDensity.current) { 340.dp.toPx() }
        val reveal = remember { derivedReveal(arrival) }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = HOLOGRAM_BOTTOM)) {
            FilyStage(time = clock, reveal = reveal)
            FilyMascot(
                state = mascotState,
                size = 190.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 22.dp)
                    .graphicsLayer {
                        // Rises from below, tilting upright as it lands; breathes with the voice.
                        val p = arrival.value
                        val voice = if (recognition == SpeechState.Listening) level.value.coerceIn(0f, 1f) else 0f
                        val bounce = 1f + 0.1f * voice
                        translationY = (1f - p) * rise
                        rotationZ = (1f - p) * -14f
                        scaleX = (0.45f + 0.55f * p) * bounce
                        scaleY = (0.45f + 0.55f * p) * bounce
                        alpha = p.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 0.6f)
                    }
                    .clip(CircleShape)
                    .semantics { contentDescription = "Fily voice assistant" }
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, enabled = enabled, onClick = { haptics.contextClick(); tapRobot() }),
            )
        }

        // Always says what Fily is doing.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = HOLOGRAM_BOTTOM + 12.dp)
                .graphicsLayer { alpha = arrival.value.coerceIn(0f, 1f) }
                .glassPanel(RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(status, color = EzColors.Text, style = MaterialTheme.typography.labelMedium)
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = HOLOGRAM_BOTTOM + 180.dp)
                .graphicsLayer { alpha = arrival.value.coerceIn(0f, 1f) },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassIconButton(EzIcons.Close, "Close Fily", close)
            GlassIconButton(EzIcons.More, if (settingsOpen) "Hide Fily settings" else "Fily settings", { settingsOpen = !settingsOpen })
        }
    }
}

/** The arrival progress clamped to 0..1, for the stage's draw-phase reads. */
private fun derivedReveal(arrival: Animatable<Float, *>): State<Float> = object : State<Float> {
    override val value: Float get() = arrival.value.coerceIn(0f, 1f)
}

@Composable
private fun FilySettings(
    enabled: Boolean,
    contents: Boolean,
    languages: SpeechLanguageSupport,
    pickedLanguage: String?,
    onEnabled: (Boolean) -> Unit,
    onContents: (Boolean) -> Unit,
    onLanguage: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Enable Fily", Modifier.weight(1f), color = EzColors.Text)
        Switch(checked = enabled, onCheckedChange = onEnabled)
    }
    if (!enabled) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Allow access to file contents", Modifier.weight(1f), color = EzColors.Text)
        Switch(checked = contents, onCheckedChange = onContents)
    }
    SpeechLanguageRow(support = languages, selected = pickedLanguage, onSelect = onLanguage)
}

/**
 * The speech language picker: installed languages first, then the ones the system can download (picking one
 * asks the system for the pack). The phone's own English stays the default until a language is picked.
 */
@Composable
internal fun SpeechLanguageRow(support: SpeechLanguageSupport, selected: String?, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Speech language", Modifier.weight(1f), color = EzColors.Text)
        EzOptionMenu(
            options = support.all,
            selected = support.all.firstOrNull { it.tag == selected },
            optionLabel = { language ->
                speechLanguageLabel(language.tag) + if (language.installed) "" else " (download)"
            },
            onSelect = { onSelect(it.tag) },
            placeholder = "Phone language",
        )
    }
}

private const val RESTART_DELAY_MS = 250L
/** How long "Speech pack ready" stays in the status pill. */
private const val PACK_RESULT_MS = 2_000L
private const val SPEECH_START_TIMEOUT_MS = 2_500L
/** About a minute of silence (each silent session lasts ~5-8 s) before Fily pauses. */
private const val MAX_QUIET_RESTARTS = 8
/** Clears the library's bottom bar. */
private val HOLOGRAM_BOTTOM = 76.dp
