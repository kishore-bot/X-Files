package com.xfiles.feature.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzOptionMenu
import com.xfiles.design.EzTextButton
import com.xfiles.design.FilyMascot
import com.xfiles.design.FilyMascotState
import com.xfiles.fily.FilySession
import com.xfiles.fily.SpeechLanguageSupport
import kotlinx.coroutines.delay

/** Modal voice controls, with conversation, microphone access, and settings in one panel. */
@Composable
fun VoiceAssistantSheet(viewModel: FilyViewModel, session: FilySession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled by viewModel.access.enabled.collectAsStateWithLifecycle()
    val contents by viewModel.access.contentAccess.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pickedLanguage by viewModel.speechLanguage.collectAsStateWithLifecycle()
    val languages by viewModel.speechLanguages.collectAsStateWithLifecycle()

    var partial by remember { mutableStateOf("") }
    var quietRestarts by remember { mutableIntStateOf(0) }
    // Whether Fily should be listening between turns; false once paused by a tap or by silence.
    var wantsToListen by rememberSaveable { mutableStateOf(true) }
    // Between a reply arriving and the voice starting, so the mic doesn't open and hear Fily itself.
    var replyPending by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var micDenied by rememberSaveable { mutableStateOf(false) }
    var permissionPending by rememberSaveable { mutableStateOf(false) }
    var hasMic by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

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
        permissionPending = false
        hasMic = granted
        micDenied = !granted
        wantsToListen = granted
        if (granted && enabled && !settingsOpen) recognizer.start()
    }
    fun startMic() {
        if (permissionPending) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            hasMic = true
            recognizer.start()
        } else {
            hasMic = false
            wantsToListen = false
            permissionPending = true
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
    LaunchedEffect(recognition, ui.working, speaking, replyPending, wantsToListen, enabled, packBusy, hasMic, settingsOpen) {
        if (!enabled || !hasMic || settingsOpen || !wantsToListen || packBusy) return@LaunchedEffect
        if (ui.working || replyPending || speaking == TtsState.Speaking) return@LaunchedEffect
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

    val close: () -> Unit = {
        recognizer.cancel()
        speaker.stop()
        viewModel.stop()
        onDismiss()
    }

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
        permissionPending -> "Waiting for microphone permission" to MaterialTheme.colorScheme.primary
        micDenied -> "Microphone permission denied" to MaterialTheme.colorScheme.error
        !hasMic -> "Microphone permission needed" to MaterialTheme.colorScheme.primary
        pack is SpeechPack.Downloading -> ((pack as SpeechPack.Downloading).percent?.let { "Downloading speech pack · $it%" } ?: "Downloading speech pack…") to Color(0xFF3E63DD)
        pack == SpeechPack.Scheduled -> "Speech pack queued · waiting for Wi-Fi" to Color(0xFFF5A524)
        pack == SpeechPack.Ready -> "Speech pack ready" to Color(0xFF30A46C)
        pack == SpeechPack.Failed -> "Speech pack download failed" to Color(0xFFE5484D)
        needsLanguage -> "Speech pack needed" to Color(0xFFF5A524)
        engineMissing -> "On-device speech recognition needed" to Color(0xFFF5A524)
        recognition == SpeechState.Error -> "Speech recognition unavailable" to Color(0xFFE5484D)
        ui.working -> "Processing…" to Color(0xFF3E63DD)
        speaking == TtsState.Error -> "Offline speech playback unavailable" to Color(0xFFE5484D)
        speaking == TtsState.Speaking -> "Speaking" to Color(0xFF8E4EC6)
        recognition == SpeechState.Listening -> "Listening · speak now" to Color(0xFF30A46C)
        wantsToListen -> "Starting…" to Color(0xFFF5A524)
        else -> "Listening paused" to EzColors.TextSecondary
    }

    Dialog(onDismissRequest = { if (settingsOpen) settingsOpen = false else close() }) {
        val colors = MaterialTheme.colorScheme
        val question = if (needsLanguage) "" else partial.ifBlank { ui.request.orEmpty() }
        val answer = when {
            !enabled -> "Fily is switched off. You can turn it on in Fily settings."
            !hasMic -> "Allow microphone access to speak to Fily. If the prompt does not appear, grant access in Android Settings."
            packBusy -> "Fetching the offline speech pack from Google. Fily starts listening as soon as it's ready."
            pack == SpeechPack.Failed -> "The speech pack didn't download. Check your connection and try again."
            needsLanguage -> "To hear you offline, your phone needs a speech pack for this language. Download it, then tap Fily."
            engineMissing -> "Fily needs an on-device speech recognizer. On Android 12 or later, install or enable a compatible speech service, then tap Fily."
            ui.working -> "Processing…"
            ui.error != null -> ui.error.orEmpty()
            else -> ui.reply.orEmpty()
        }
        Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerHigh, contentColor = colors.onSurface) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Fily", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = {
                        if (!settingsOpen) recognizer.cancel()
                        settingsOpen = !settingsOpen
                    }) {
                        Icon(EzIcons.Settings, if (settingsOpen) "Close Fily settings" else "Fily settings")
                    }
                    IconButton(onClick = close) { Icon(EzIcons.Close, "Close Fily") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
                    Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
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
                        Text(question, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (answer.isNotBlank()) {
                        Text(
                            answer,
                            color = if (ui.error != null && !ui.working) MaterialTheme.colorScheme.error else colors.onSurface,
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
                        !enabled -> EzTextButton("Open Fily settings", { settingsOpen = true })
                        permissionPending -> Unit
                        !hasMic -> EzTextButton("Allow microphone", {
                            micDenied = false
                            startMic()
                        })
                        pack == SpeechPack.Failed -> EzTextButton("Try again", { downloadPack() })
                        needsLanguage -> EzTextButton("Download speech pack", { downloadPack() })
                        engineMissing -> EzTextButton("Get Google speech", recognizer::getSpeechEngine)
                    }
                }
                if (enabled && hasMic && !settingsOpen) {
                    val action = when {
                        speaking == TtsState.Speaking || replyPending -> "Interrupt"
                        ui.working -> "Processing"
                        recognition == SpeechState.Listening -> "Pause listening"
                        else -> "Start listening"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilyMascot(state = mascotState, size = 56.dp)
                        if (!ui.working && !packBusy && !needsLanguage && !engineMissing) {
                            EzTextButton(action, ::tapRobot)
                        }
                    }
                }
            }
        }
    }
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
