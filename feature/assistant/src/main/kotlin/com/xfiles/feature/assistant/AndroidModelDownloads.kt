package com.xfiles.feature.assistant

import android.content.Context
import android.net.Uri
import android.util.Log
import com.xfiles.fily.AssistantModel
import com.xfiles.fily.ModelDownloads
import com.xfiles.fily.ModelFile
import com.xfiles.fily.ModelInstall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Models live in the app's private data folder (`files/models/<model>/`), which only Xfiles can read
 * and which Android removes with the app. A download resumes where it stopped; every file is checked
 * against its SHA-256 before it is used, and only then moved into place.
 */
@javax.inject.Singleton
internal class AndroidModelDownloads @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) : ModelDownloads {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "models")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = AssistantModel.entries.associateWith { MutableStateFlow(if (folder(it) != null) ModelInstall.Installed else ModelInstall.Missing) }
    private val jobs = HashMap<AssistantModel, Job>()

    init {
        // The Moonshine speech model of earlier versions is no longer used: give its space back.
        scope.launch { File(root, "moonshine-base-en").deleteRecursively() }
    }

    override fun state(model: AssistantModel): StateFlow<ModelInstall> = states.getValue(model).asStateFlow()

    /** The private copy, else a developer's copy pushed with adb (`/data/local/tmp`), if complete. */
    override fun folder(model: AssistantModel): String? = listOf(dir(model), devDir(model))
        .firstOrNull { folder -> model.files.all { File(folder, it.name).length() == it.bytes } }?.path

    private fun devDir(model: AssistantModel) = when (model) {
        AssistantModel.Fily -> File("/data/local/tmp")
    }

    override fun download(model: AssistantModel) = run(model) {
        val missing = model.files.filter { !File(dir(model), it.name).isFile }
        val needed = missing.sumOf { it.bytes - part(model, it).length() }
        if (dir(model).also { it.mkdirs() }.usableSpace < needed + SPARE_BYTES) {
            throw IOException("Not enough space: ${needed / 1_000_000 + 50} MB free space is needed.")
        }
        var done = model.files.sumOf { file -> if (File(dir(model), file.name).isFile) file.bytes else part(model, file).length() }
        for (file in missing) {
            val target = part(model, file)
            val connection = (URI(AssistantModel.RELEASE_URL + file.name).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                if (target.length() > 0) setRequestProperty("Range", "bytes=${target.length()}-")
            }
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> { done -= target.length(); target.delete() } // No resume: start the file again.
                    HttpURLConnection.HTTP_PARTIAL -> {}
                    HttpURLConnection.HTTP_NOT_FOUND ->
                        throw IOException("The model isn't available to download yet. Ask the developer, or import the file instead.")
                    else -> throw IOException("The download server answered ${connection.responseCode}. Try again later.")
                }
                connection.inputStream.use { input ->
                    // Appends to a partial file (resumed); after HTTP 200 it was deleted, so this starts fresh.
                    java.io.FileOutputStream(target, true).use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            states.getValue(model).value = ModelInstall.Downloading(done, model.bytes)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            settle(model, file, target)
        }
    }

    override fun import(model: AssistantModel, uris: List<String>) = run(model) {
        dir(model).mkdirs()
        val left = model.files.filter { !File(dir(model), it.name).isFile }.toMutableList()
        for (uri in uris) {
            val size = app.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length } ?: -1
            val file = left.firstOrNull { it.bytes == size } ?: throw IOException("A chosen file isn't part of the ${model.label}.")
            val target = part(model, file)
            target.delete()
            val input: InputStream = app.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("The chosen file can't be read.")
            input.use { stream -> target.outputStream().use { stream.copyTo(it, 256 * 1024) } }
            settle(model, file, target)
            left -= file
        }
        if (left.isNotEmpty()) throw IOException("Still needed: ${left.joinToString { it.name }}.")
    }

    override fun cancel(model: AssistantModel) {
        jobs.remove(model)?.cancel()
        states.getValue(model).value = if (folder(model) != null) ModelInstall.Installed else ModelInstall.Missing
    }

    override fun delete(model: AssistantModel) {
        cancel(model)
        dir(model).deleteRecursively()
        states.getValue(model).value = if (folder(model) != null) ModelInstall.Installed else ModelInstall.Missing
    }

    private fun run(model: AssistantModel, work: suspend () -> Unit) {
        if (jobs[model]?.isActive == true) return
        states.getValue(model).value = ModelInstall.Downloading(0, model.bytes)
        jobs[model] = scope.launch {
            states.getValue(model).value = try {
                work()
                if (folder(model) != null) ModelInstall.Installed else ModelInstall.Missing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnknownHostException) {
                ModelInstall.Failed("No internet connection. Connect to Wi-Fi and try again.")
            } catch (e: Exception) {
                Log.w(TAG, "${model.name} install failed: ${e.message}")
                ModelInstall.Failed(e.message ?: "The download failed. Try again.")
            }
        }
    }

    /** Checks the finished file and moves it into place; a damaged one is deleted. */
    private suspend fun settle(model: AssistantModel, file: ModelFile, part: File) {
        states.getValue(model).value = ModelInstall.Verifying
        if (part.length() != file.bytes || sha256(part) != file.sha256) {
            part.delete()
            throw IOException("${file.name} was damaged on the way. Try again.")
        }
        if (!part.renameTo(File(dir(model), file.name))) throw IOException("${file.name} couldn't be saved.")
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Files are only ever in place after their checksum passed, so presence and size suffice here. */
    private fun installed(model: AssistantModel) = model.files.all { File(dir(model), it.name).length() == it.bytes }

    private fun dir(model: AssistantModel) = File(root, model.folder)

    private fun part(model: AssistantModel, file: ModelFile) = File(dir(model), file.name + ".part")

    private companion object {
        const val TAG = "EzModels"
        const val SPARE_BYTES = 50_000_000L
    }
}
