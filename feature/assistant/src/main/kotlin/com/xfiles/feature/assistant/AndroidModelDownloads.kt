package com.xfiles.feature.assistant

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import com.xfiles.fily.AssistantModel
import com.xfiles.fily.ModelDownloads
import com.xfiles.fily.ModelFile
import com.xfiles.fily.ModelInstall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Models live in the app's private data folder (`files/models/<model>/`), which only Xfiles can read
 * and which Android removes with the app. A download can resume after an interrupted response; every file is checked
 * against its SHA-256 before it is used, and only then moved into place.
 */
@javax.inject.Singleton
internal class AndroidModelDownloads @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) : ModelDownloads {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "models")
    /** Declared before [states], whose initializer calls [folder]. */
    private val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = AssistantModel.entries.associateWith { MutableStateFlow(if (folder(it) != null) ModelInstall.Installed else ModelInstall.Missing) }
    private val lock = Any()
    private val jobs = HashMap<AssistantModel, Job>()

    private val startupCleanup = scope.launch {
        File(root, "moonshine-base-en").deleteRecursively()
        // No attempt creates a directory until leftovers from an interrupted process are removed.
        root.listFiles()?.filter { it.name.startsWith(".install-") }?.forEach { it.deleteRecursively() }
    }

    override fun state(model: AssistantModel): StateFlow<ModelInstall> = states.getValue(model).asStateFlow()

    /**
     * The private copy, else (debuggable builds only) a developer's copy pushed with adb (`/data/local/tmp`), if
     * complete. The adb copy is not checksummed, so release builds never look there.
     */
    override fun folder(model: AssistantModel): String? = listOfNotNull(dir(model), devDir(model))
        .firstOrNull { folder -> model.files.all { ready(folder, it) } }?.path

    private fun devDir(model: AssistantModel): File? = if (!debuggable) null else when (model) {
        AssistantModel.Fily -> File("/data/local/tmp")
    }

    override fun download(model: AssistantModel) = run(model) { attempt ->
        val missing = model.files.filter { !ready(dir(model), it) }
        val needed = missing.sumOf { it.bytes - resumeOffset(model, it) }
        if (owned(model) { dir(model).also { it.mkdirs() }.usableSpace } < needed + SPARE_BYTES) {
            throw IOException("Not enough space: ${needed / 1_000_000 + 50} MB free space is needed.")
        }
        var done = model.files.sumOf { file -> if (ready(dir(model), file)) file.bytes else resumeOffset(model, file) }
        for (file in missing) {
            val target = File(attempt, file.name)
            owned(model) {
                val resume = resumeFile(model, file)
                if (resume.length() in 1L until file.bytes && !resume.renameTo(target)) {
                    throw IOException("The partial download could not be reopened.")
                }
            }
            val offset = target.length()
            val connection = (URI(AssistantModel.RELEASE_URL + file.name).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> { done -= offset; target.outputStream().close() } // No resume: start the file again.
                    HttpURLConnection.HTTP_PARTIAL -> {
                        if (offset == 0L || connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") != true) {
                            throw IOException("The server returned an unexpected download range.")
                        }
                    }
                    HttpURLConnection.HTTP_NOT_FOUND ->
                        throw IOException("The model isn't available to download yet. Ask the developer, or import the file instead.")
                    else -> throw IOException("The download server answered ${connection.responseCode}. Try again later.")
                }
                connection.inputStream.use { input ->
                    copyModel(input, target, file.bytes, append = true) { count ->
                        done += count
                        publish(model, ModelInstall.Downloading(done, model.bytes))
                    }
                }
                settle(model, file, target)
            } catch (error: IOException) {
                if (target.length() in 1L until file.bytes) {
                    owned(model) { target.renameTo(resumeFile(model, file)) }
                }
                throw error
            } finally {
                connection.disconnect()
            }
        }
    }

    override fun import(model: AssistantModel, uris: List<String>) = run(model) { attempt ->
        owned(model) { dir(model).mkdirs() }
        val left = model.files.filter { !ready(dir(model), it) }.toMutableList()
        for (uri in uris) {
            val size = app.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length } ?: -1
            val file = left.firstOrNull { it.bytes == size } ?: throw IOException("A chosen file isn't part of the ${model.label}.")
            val target = File(attempt, file.name)
            val input: InputStream = app.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("The chosen file can't be read.")
            input.use { copyModel(it, target, file.bytes) {} }
            settle(model, file, target)
            left -= file
        }
        if (left.isNotEmpty()) throw IOException("Still needed: ${left.joinToString { it.name }}.")
    }

    override fun cancel(model: AssistantModel) {
        synchronized(lock) {
            jobs.remove(model)?.cancel()
            states.getValue(model).value = readiness(model)
        }
    }

    override fun delete(model: AssistantModel) {
        synchronized(lock) {
            jobs.remove(model)?.cancel()
            dir(model).deleteRecursively()
            states.getValue(model).value = readiness(model)
        }
    }

    private fun readiness(model: AssistantModel) =
        if (folder(model) != null) ModelInstall.Installed else ModelInstall.Missing

    private fun run(model: AssistantModel, work: suspend (File) -> Unit) {
        synchronized(lock) {
            if (jobs[model]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val attempt = File(root, ".install-${UUID.randomUUID()}")
                try {
                    val result = try {
                        startupCleanup.join()
                        if (!attempt.mkdirs()) throw IOException("The model workspace is unavailable.")
                        work(attempt)
                        readiness(model)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: UnknownHostException) {
                        ModelInstall.Failed("No internet connection. Connect to Wi-Fi and try again.")
                    } catch (error: Exception) {
                        Log.w(TAG, "${model.name} install failed", error)
                        ModelInstall.Failed(error.message ?: "The download failed. Try again.")
                    }
                    owned(model) {
                        states.getValue(model).value = result
                        jobs.remove(model)
                    }
                } finally {
                    attempt.deleteRecursively()
                }
            }
            jobs[model] = job
            states.getValue(model).value = ModelInstall.Downloading(0, model.bytes)
            job.start()
        }
    }

    private suspend fun <T> owned(model: AssistantModel, action: () -> T): T {
        val owner = coroutineContext[Job]
        return synchronized(lock) {
            if (jobs[model] !== owner || owner?.isActive != true) throw CancellationException()
            action()
        }
    }

    private suspend fun publish(model: AssistantModel, state: ModelInstall) {
        owned(model) { states.getValue(model).value = state }
    }

    private suspend fun copyModel(
        input: InputStream, target: File, limit: Long, append: Boolean = false, onBytes: suspend (Int) -> Unit,
    ) {
        var total = if (append) target.length() else 0L
        java.io.FileOutputStream(target, append).use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                total += size
                if (total > limit) throw IOException("The model file is larger than expected.")
                output.write(buffer, 0, size)
                onBytes(size)
            }
        }
    }

    /** Checks the complete file before replacing any invalid installed copy. */
    private suspend fun settle(model: AssistantModel, file: ModelFile, part: File) {
        publish(model, ModelInstall.Verifying)
        if (part.length() != file.bytes || sha256(part) != file.sha256) {
            part.delete()
            throw IOException("${file.name} was damaged on the way. Try again.")
        }
        owned(model) {
            if (!part.renameTo(File(dir(model), file.name))) throw IOException("${file.name} couldn't be saved.")
            resumeFile(model, file).delete()
        }
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

    /**
     * Files in the private folder are only ever in place after their checksum passed, so presence and size suffice
     * there. The adb folder is unverified and only consulted by debuggable builds.
     */
    private fun ready(folder: File, file: ModelFile) = File(folder, file.name).let { it.isFile && it.length() == file.bytes }

    private fun dir(model: AssistantModel) = File(root, model.folder)

    private fun resumeOffset(model: AssistantModel, file: ModelFile): Long =
        resumeFile(model, file).length().takeIf { it in 1L until file.bytes } ?: 0L

    private fun resumeFile(model: AssistantModel, file: ModelFile) = File(dir(model), file.name + ".part")

    private companion object {
        const val TAG = "EzModels"
        const val SPARE_BYTES = 50_000_000L
    }
}
