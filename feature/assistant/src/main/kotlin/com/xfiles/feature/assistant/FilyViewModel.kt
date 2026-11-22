package com.xfiles.feature.assistant

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.app.Application
import android.os.Build
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.documentengine.FilyGeneration
import com.xfiles.documentengine.FilyInference
import com.xfiles.fily.AssistantAccess
import com.xfiles.fily.AssistantModel
import com.xfiles.fily.FilyDocuments
import com.xfiles.fily.FilyNavigator
import com.xfiles.fily.FilyGpuGuard
import com.xfiles.fily.FilyPrefKeys
import com.xfiles.fily.FilyPreferences
import com.xfiles.fily.FilyPrompt
import com.xfiles.fily.FilySession
import com.xfiles.fily.FilyTidy
import com.xfiles.fily.ModelInstall
import com.xfiles.fily.ReaderControl
import com.xfiles.fily.SpeechLanguageSupport
import com.xfiles.fily.ToolExecutor
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class FilyViewModel @Inject internal constructor(
    application: Application,
    private val downloads: AndroidModelDownloads,
    private val inference: FilyInference,
    /** The process-wide policy, shared with the About switches and the Android AppFunctions. */
    val access: AssistantAccess,
    private val preferences: FilyPreferences,
) : AndroidViewModel(application) {
    val install = downloads.state(AssistantModel.Fily)
    private val mutableUi = MutableStateFlow(FilyUiState())
    val ui = mutableUi.asStateFlow()
    private val mutableGeneration = MutableStateFlow<FilyGeneration?>(null)
    val generation = mutableGeneration.asStateFlow()
    private var request: Job? = null
    /** Set while the loaded model runs on the GPU; its answers are what prove or rule out the GPU. */
    private var gpuGuard: FilyGpuGuard? = null

    private val mutableSpeechLanguage = MutableStateFlow(preferences.get(FilyPrefKeys.SPEECH_LANGUAGE)?.takeIf(String::isNotBlank))
    /** The picked recognition and speech language (BCP-47), or null for the phone's own English. */
    val speechLanguage: StateFlow<String?> = mutableSpeechLanguage.asStateFlow()
    private val mutableSpeechLanguages = MutableStateFlow(SpeechLanguageSupport())
    val speechLanguages: StateFlow<SpeechLanguageSupport> = mutableSpeechLanguages.asStateFlow()
    /** Kept alive while the system downloads a language pack the user asked for. */
    private var languageDownloader: SpeechRecognizer? = null

    fun createSession(
        documents: FilyDocuments,
        reader: ReaderControl,
        fileNames: () -> List<String>,
        navigator: FilyNavigator,
        tidy: FilyTidy? = null,
    ): FilySession {
        val tools = ToolExecutor(access, documents, reader, navigator, tidy)
        return FilySession(
            complete = { prompt, grammar ->
                val result = inference.complete(prompt, grammar)
                gpuGuard?.let { guard ->
                    withContext(Dispatchers.IO) { guard.answered(result.gpu) }
                    if (!result.gpu) gpuGuard = null
                }
                mutableGeneration.value = result
                result.text
            },
            tools = tools,
            access = access,
            today = { LocalDate.now().toString() },
            reader = reader,
            fileNames = fileNames,
        )
    }

    fun setEnabled(enabled: Boolean) {
        access.setEnabled(enabled)
        if (!enabled) stop()
    }

    fun setContentAccess(allowed: Boolean) {
        access.setContentAccess(allowed)
        if (!allowed) stop()
    }

    fun download() = downloads.download(AssistantModel.Fily)
    fun import(uri: String) = downloads.import(AssistantModel.Fily, listOf(uri))
    fun cancelDownload() = downloads.cancel(AssistantModel.Fily)

    /** Removes the Fily model from the phone; Fily stops first, so nothing is answering from the files. */
    fun deleteModel() {
        stop()
        downloads.delete(AssistantModel.Fily)
    }

    /** Lists the recognizer's languages (installed and downloadable). Off the main thread except the system call. */
    fun refreshSpeechLanguages() {
        viewModelScope.launch {
            mutableSpeechLanguages.value = try {
                querySpeechLanguages(getApplication())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                Log.w("XfilesFily", "Speech languages unavailable", error)
                SpeechLanguageSupport()
            }
        }
    }

    /** Remembers the pick; null goes back to the phone's English. Takes effect at the next listen. */
    fun selectSpeechLanguage(tag: String?) {
        preferences.put(FilyPrefKeys.SPEECH_LANGUAGE, tag.orEmpty())
        mutableSpeechLanguage.value = tag
    }

    /** Asks the system for an offline pack; the list is refreshed when the user reopens the picker. */
    fun downloadSpeechLanguage(tag: String) {
        viewModelScope.launch {
            languageDownloader?.destroy()
            languageDownloader = triggerSpeechLanguageDownload(getApplication(), tag)
        }
    }

    fun ask(session: FilySession, alternatives: List<String>) {
        if (!access.enabled.value || install.value != ModelInstall.Installed || request?.isActive == true) return
        val words = session.bestTranscript(alternatives).takeIf { it.isNotBlank() } ?: return
        val folder = downloads.folder(AssistantModel.Fily) ?: return
        val path = File(folder, AssistantModel.Fily.files.single().name).absolutePath
        mutableUi.value = FilyUiState(request = words, working = true)
        request = viewModelScope.launch {
            try {
                ensureModel(path, gpuWanted = Build.HARDWARE.contains("qcom", ignoreCase = true))
                access.requireEnabled()
                val reply = session.ask(words)
                if (access.enabled.value) mutableUi.value = FilyUiState(request = words, reply = reply)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesFily", "On-device inference failed", error)
                mutableUi.value = FilyUiState(request = words, error = "Fily could not answer. Try again.")
            } catch (error: LinkageError) {
                Log.e("XfilesFily", "Native inference unavailable", error)
                mutableUi.value = FilyUiState(request = words, error = "Fily’s local model is unavailable on this device.")
            }
        }
    }

    fun stop() {
        request?.cancel()
        inference.cancel()
        inference.close()
        gpuGuard = null
        mutableUi.value = FilyUiState()
    }

    /**
     * Loads the model unless it is already the loaded one. The GPU guard is consulted only for a real load,
     * so a running GPU model never reads its own "trying" state as a crash. Disk work runs on IO.
     */
    private suspend fun ensureModel(path: String, gpuWanted: Boolean) {
        if (inference.isLoaded(path)) return
        withContext(Dispatchers.IO) {
            val guard = FilyGpuGuard(preferences, buildStamp(path))
            val useGpu = gpuWanted && guard.startLoad(preferGpu = true)
            val onGpu = inference.load(path, useGpu, FilyPrompt.STABLE_PREFIX)
            if (useGpu && !onGpu) guard.failed()
            gpuGuard = guard.takeIf { onGpu }
        }
    }

    /** Changes with the app build (version and install time) and with the model file, so each gets one GPU attempt. */
    private fun buildStamp(path: String): String {
        val app = getApplication<Application>()
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val code = when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> info.versionCode.toLong()
        }
        val model = File(path)
        return "$code.${info?.lastUpdateTime ?: 0L}.${model.length()}.${model.lastModified()}"
    }

    override fun onCleared() {
        stop()
        languageDownloader?.destroy()
        languageDownloader = null
        super.onCleared()
    }
}

data class FilyUiState(
    val request: String? = null,
    val reply: String? = null,
    val working: Boolean = false,
    val error: String? = null,
)
