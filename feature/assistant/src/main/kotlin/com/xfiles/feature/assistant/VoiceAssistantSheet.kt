package com.xfiles.feature.assistant

import androidx.compose.runtime.SideEffect
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.glassPanel
import com.xfiles.design.glossyPrimary
import com.xfiles.fily.FilySession
import com.xfiles.fily.ModelInstall

@Composable
fun VoiceAssistantSheet(viewModel: FilyViewModel, session: FilySession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled by viewModel.access.enabled.collectAsStateWithLifecycle()
    val contents by viewModel.access.contentAccess.collectAsStateWithLifecycle()
    val install by viewModel.install.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val recognizer = rememberSpeechRecognition(hints = session::speechHints) { result ->
        if (result.isFinal) viewModel.ask(session, result.alternatives)
    }
    val recognition by recognizer.state.collectAsStateWithLifecycle()
    val speaker = rememberTextToSpeech()
    val speaking by speaker.state.collectAsStateWithLifecycle()
    SideEffect(ui.reply) { ui.reply?.let(speaker::speak) }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recognizer.start()
    }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri.toString())
    }
    val close: () -> Unit = {
        recognizer.cancel()
        speaker.stop()
        viewModel.stop()
        onDismiss()
    }

    Dialog(onDismissRequest = close) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).glassPanel(EzShapes.Dialog).verticalScroll(rememberScrollState()).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Fily", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = close) { Icon(EzIcons.Close, "Close Fily") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable Fily", Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    if (!it) {
                        recognizer.cancel()
                        speaker.stop()
                        session.clear()
                    }
                    viewModel.setEnabled(it)
                })
            }
            if (enabled) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow access to file contents", Modifier.weight(1f))
                    Switch(checked = contents, onCheckedChange = {
                        if (!it) {
                            session.clear()
                            speaker.stop()
                        }
                        viewModel.setContentAccess(it)
                    })
                }
                when (val state = install) {
                    ModelInstall.Missing, is ModelInstall.Failed -> {
                        if (state is ModelInstall.Failed) Text(state.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::download) { Text("Download Fily model") }
                        TextButton(onClick = { importModel.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Import model file") }
                    }
                    is ModelInstall.Downloading -> {
                        LinearProgressIndicator(progress = { (state.done.toFloat() / state.total.coerceAtLeast(1L)).coerceIn(0f, 1f) })
                        TextButton(onClick = viewModel::cancelDownload) { Text("Cancel download") }
                    }
                    ModelInstall.Verifying -> {
                        LinearProgressIndicator()
                        Text("Verifying model")
                    }
                    ModelInstall.Installed -> {
                        val listening = recognition == SpeechState.Listening
                        Box(
                            Modifier.size(86.dp).glossyPrimary(CircleShape)
                                .clickable(enabled = !ui.working, role = Role.Button) {
                                    speaker.stop()
                                    if (listening) {
                                        recognizer.stop()
                                    } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        recognizer.start()
                                    } else {
                                        microphone.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (listening) EzIcons.Close else EzIcons.Mic, if (listening) "Finish speaking" else "Speak to Fily", tint = EzColors.OnPrimary)
                        }
                        when (recognition) {
                            SpeechState.Listening -> Text("Listening")
                            SpeechState.NeedsLanguage -> TextButton(onClick = recognizer::downloadLanguage) { Text("Get offline English") }
                            SpeechState.Error -> Text("On-device speech recognition is unavailable.", color = MaterialTheme.colorScheme.error)
                            SpeechState.Idle -> Text(if (speaking == TtsState.Speaking) "Speaking" else "Tap to speak")
                        }
                    }
                }
                if (ui.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                ui.request?.let { Text(it, color = EzColors.TextSecondary, textAlign = TextAlign.Center) }
                ui.reply?.let { Text(it, textAlign = TextAlign.Center) }
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
        }
    }
}
