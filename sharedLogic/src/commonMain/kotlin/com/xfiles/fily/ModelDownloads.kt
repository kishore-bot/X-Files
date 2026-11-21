package com.xfiles.fily

import kotlinx.coroutines.flow.StateFlow

/** One file of a downloadable model, checked by size and SHA-256 before it is used. */
data class ModelFile(val name: String, val bytes: Long, val sha256: String)

/**
 * The AI models EzFiles downloads on request (they are too large for the app itself). They are
 * published as assets of a GitHub release, [RELEASE_URL] + file name, and stored in the app's
 * private data folder.
 */
enum class AssistantModel(val label: String, val folder: String, val files: List<ModelFile>) {
    Fily(
        "Fily assistant model", "fily-v8",
        listOf(ModelFile("Fily-EzFiles-350M-Q8_0.gguf", 379_220_000, "44b6af9cb9e94c07509cbfe7c6107504c7eb40dadbb70edeaedf1b98d127de0a")),
    ),
    ;

    val bytes: Long get() = files.sumOf { it.bytes }

    companion object {
        /** Release assets: each file is downloaded from this URL plus its name. */
        const val RELEASE_URL = "https://github.com/kishore-bot/EzFiles/releases/download/models-v1/"
    }
}

sealed interface ModelInstall {
    data object Missing : ModelInstall
    /** [done] of [total] bytes. */
    data class Downloading(val done: Long, val total: Long) : ModelInstall
    data object Verifying : ModelInstall
    data object Installed : ModelInstall
    data class Failed(val message: String) : ModelInstall
}

/** Downloads, imports and locates the models. */
interface ModelDownloads {
    fun state(model: AssistantModel): StateFlow<ModelInstall>

    /** Downloads every missing file, resuming a partial one; a no-op while one is running. */
    fun download(model: AssistantModel)

    fun cancel(model: AssistantModel)

    /**
     * Copies model files the user downloaded themselves (e.g. from GitHub in the browser). Each
     * picked file is matched to the model's files by size and checked by SHA-256.
     */
    fun import(model: AssistantModel, uris: List<String>)

    /** The installed folder, or null. */
    fun folder(model: AssistantModel): String?

    fun delete(model: AssistantModel)
}
