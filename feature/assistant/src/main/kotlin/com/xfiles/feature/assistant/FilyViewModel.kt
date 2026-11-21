package com.xfiles.feature.assistant

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.documentengine.FilyGeneration
import com.xfiles.documentengine.FilyInference
import com.xfiles.fily.AssistantAccess
import com.xfiles.fily.AssistantModel
import com.xfiles.fily.FilyDocuments
import com.xfiles.fily.FilyNavigator
import com.xfiles.fily.FilyTidy
import com.xfiles.fily.FilyPreferences
import com.xfiles.fily.FilyPrompt
import com.xfiles.fily.FilySession
import com.xfiles.fily.ModelInstall
import com.xfiles.fily.ReaderControl
import com.xfiles.fily.ToolExecutor
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class FilyViewModel @Inject internal constructor(
    application: Application,
    private val downloads: AndroidModelDownloads,
    private val inference: FilyInference,
) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("fily", Application.MODE_PRIVATE)
    private val policy = object : FilyPreferences {
        override fun get(key: String): String? = preferences.getString(key, null)
        override fun put(key: String, value: String) {
            preferences.edit().putString(key, value).apply()
        }
    }
    val access = AssistantAccess(policy)
    val install = downloads.state(AssistantModel.Fily)
    private val mutableUi = MutableStateFlow(FilyUiState())
    val ui = mutableUi.asStateFlow()
    private val mutableGeneration = MutableStateFlow<FilyGeneration?>(null)
    val generation = mutableGeneration.asStateFlow()
    private var request: Job? = null

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

    fun ask(session: FilySession, alternatives: List<String>) {
        if (!access.enabled.value || install.value != ModelInstall.Installed || request?.isActive == true) return
        val words = session.bestTranscript(alternatives).takeIf { it.isNotBlank() } ?: return
        val folder = downloads.folder(AssistantModel.Fily) ?: return
        val path = File(folder, AssistantModel.Fily.files.single().name).absolutePath
        mutableUi.value = FilyUiState(request = words, working = true)
        request = viewModelScope.launch {
            try {
                val gpu = Build.HARDWARE.contains("qcom", ignoreCase = true)
                inference.load(path, gpu, FilyPrompt.STABLE_PREFIX)
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
        mutableUi.value = FilyUiState()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

data class FilyUiState(
    val request: String? = null,
    val reply: String? = null,
    val working: Boolean = false,
    val error: String? = null,
)
