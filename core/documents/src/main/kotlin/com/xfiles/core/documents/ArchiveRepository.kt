package com.xfiles.core.documents

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.xfiles.documents.archive.ArchiveEntry
import com.xfiles.documents.archive.ArchiveTree
import com.xfiles.engine.ffi.ArchiveSession
import com.xfiles.engine.ffi.NativeArchiveProgress
import com.xfiles.engine.ffi.ReadException
import com.xfiles.engine.ffi.openArchiveFd
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why an archive operation failed. The UI maps each reason to one message. */
enum class ArchiveError {
    /** Encrypted with a scheme the engine does not implement. */
    Encrypted,
    /** Encrypted with a supported scheme; a password is needed. */
    PasswordRequired,
    /** The password given does not open the entry. */
    WrongPassword,
    TooLarge, Unsafe, Malformed, Unsupported, Cancelled, Io,
}

class ArchiveException(val reason: ArchiveError, message: String) : Exception(message)

/** Progress of an extraction or copy. [fraction] is 0..1 and never goes backwards within a phase. */
data class ArchiveProgress(
    val phase: Phase,
    val bytesDone: Long,
    val bytesTotal: Long,
    val entriesDone: Int,
    val entriesTotal: Int,
) {
    enum class Phase { Decompressing, Copying, Done }

    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            entriesTotal > 0 -> (entriesDone.toFloat() / entriesTotal).coerceIn(0f, 1f)
            else -> 0f
        }
}

/**
 * Engine access for the archive browser.
 *
 * Every engine call runs on [lane], a two-thread IO lane, so a large
 * extraction cannot starve page previews. Each operation opens its own
 * session on a fresh descriptor, so sessions never share a file offset, and
 * each session is closed when its operation ends.
 *
 * Passwords are passed per call and never stored here. The caller decides how
 * long to keep one (the browser keeps it for its session only).
 */
@Singleton
class ArchiveRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val lane = Dispatchers.IO.limitedParallelism(2)

    /** Cache cleanup outlives any one ViewModel, so it runs on its own scope. */
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Start-up sweep: anything left in the single-entry cache belongs to an earlier run.
        cleanup.launch { File(context.cacheDir, OPEN_FOLDER).deleteRecursively() }
    }

    /** Builds the folder tree: entries are read on [lane], the tree is built on Default. */
    suspend fun index(uri: Uri): ArchiveTree {
        val entries = withContext(lane) {
            openSession(uri).use { session ->
                session.entries().map {
                    ArchiveEntry(
                        path = it.path,
                        size = it.size.toLong(),
                        compressedSize = it.compressedSize.toLong(),
                        isDirectory = it.isDir,
                        encrypted = it.encrypted,
                        modifiedDos = it.modifiedDos.toInt(),
                    )
                }
            }
        }
        return withContext(Dispatchers.Default) { ArchiveTree.build(entries) }
    }

    /**
     * Extracts [paths] into [staging], emitting [ArchiveProgress.Phase.Decompressing]
     * updates about every 120 ms. Cancelling the collector cancels the native
     * work at its next check point and waits for it to stop before returning.
     */
    fun extractToStaging(
        uri: Uri,
        paths: List<String>,
        staging: File,
        password: String? = null,
    ): Flow<ArchiveProgress> = channelFlow {
        val session = withContext(lane) { openSession(uri) }
        try {
            coroutineScope {
                val poller = launch {
                    while (true) {
                        send(session.progress().toModel(ArchiveProgress.Phase.Decompressing))
                        delay(POLL_MILLIS)
                    }
                }
                val worker = async(lane) { session.extract(paths, staging.absolutePath, password) }
                try {
                    worker.await()
                } catch (cancelled: CancellationException) {
                    session.cancel()
                    withContext(NonCancellable) { worker.join() }
                    throw cancelled
                } catch (failure: ReadException) {
                    throw failure.toArchive()
                } finally {
                    poller.cancel()
                }
            }
        } finally {
            session.close()
        }
    }

    /**
     * Extracts [paths] to a cache staging folder, then copies them into the
     * folder the user picked with OpenDocumentTree. Staging is removed afterwards.
     */
    fun exportToTree(
        uri: Uri,
        paths: List<String>,
        treeUri: Uri,
        password: String? = null,
    ): Flow<ArchiveProgress> = channelFlow {
        val staging = File(context.cacheDir, "archive-stage/${System.nanoTime()}")
        try {
            extractToStaging(uri, paths, staging, password).collect { send(it) }
            copyStagingToTree(staging, treeUri) { send(it) }
            send(ArchiveProgress(ArchiveProgress.Phase.Done, 0, 0, 0, 0))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { staging.deleteRecursively() }
        }
    }

    /**
     * Extracts one entry into the app cache and returns a `content://` URI for
     * the reader. The cache holds one file at a time: previous open entries are
     * removed first, so the cache stays bounded by a single entry.
     */
    suspend fun openEntry(uri: Uri, path: String, password: String? = null): Uri {
        val folder = File(context.cacheDir, OPEN_FOLDER)
        withContext(Dispatchers.IO) {
            folder.deleteRecursively()
            folder.mkdirs()
        }
        extractToStaging(uri, listOf(path), folder, password).collect { }
        val relative = safeRelative(path) ?: throw ArchiveException(ArchiveError.Unsafe, "$path is not a safe name")
        val file = File(folder, relative.joinToString("/"))
        return FileProvider.getUriForFile(context, "${context.packageName}$PROVIDER_SUFFIX", file)
    }

    /**
     * Removes the single-entry cache without blocking the caller. Called when
     * the browser's ViewModel is cleared, i.e. when the reader leaves the archive.
     */
    fun releaseOpenCache() {
        cleanup.launch { File(context.cacheDir, OPEN_FOLDER).deleteRecursively() }
    }

    private suspend fun copyStagingToTree(staging: File, treeUri: Uri, emit: suspend (ArchiveProgress) -> Unit) {
        withContext(lane) {
            val resolver = context.contentResolver
            val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val folders = HashMap<String, Uri>().apply { put("", root) }
            val files = staging.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
            val bytesTotal = files.sumOf { it.length() }
            var bytesDone = 0L
            files.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val relative = file.relativeTo(staging).invariantSeparatorsPath
                val parts = relative.split('/')
                var parentPath = ""
                var parentUri = root
                for (folder in parts.dropLast(1)) {
                    val folderPath = if (parentPath.isEmpty()) folder else "$parentPath/$folder"
                    parentUri = folders[folderPath] ?: DocumentsContract.createDocument(
                        resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, folder,
                    )?.also { folders[folderPath] = it } ?: throw ArchiveException(ArchiveError.Io, "Could not create $folder")
                    parentPath = folderPath
                }
                val target = DocumentsContract.createDocument(
                    resolver, parentUri, "application/octet-stream", parts.last(),
                ) ?: throw ArchiveException(ArchiveError.Io, "Could not create ${parts.last()}")
                try {
                    resolver.openOutputStream(target)?.use { output ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(COPY_BUFFER)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                bytesDone += read
                                emit(ArchiveProgress(ArchiveProgress.Phase.Copying, bytesDone, bytesTotal, index, files.size))
                            }
                        }
                    } ?: throw ArchiveException(ArchiveError.Io, "Could not write ${parts.last()}")
                } catch (failure: Throwable) {
                    // Do not leave a half-written file behind when the copy stops.
                    runCatching { DocumentsContract.deleteDocument(resolver, target) }
                    throw failure
                }
            }
        }
    }

    private fun openSession(uri: Uri): ArchiveSession {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw ArchiveException(ArchiveError.Io, "The archive could not be opened.")
        // The engine takes ownership of the descriptor and closes it.
        val fd = descriptor.detachFd()
        return try {
            openArchiveFd(fd)
        } catch (failure: ReadException) {
            throw failure.toArchive()
        }
    }

    private fun ReadException.toArchive(): ArchiveException {
        val reason = when (this) {
            is ReadException.UnsupportedEncryption -> ArchiveError.Encrypted
            is ReadException.PasswordRequired -> ArchiveError.PasswordRequired
            is ReadException.WrongPassword -> ArchiveError.WrongPassword
            is ReadException.LimitExceeded -> ArchiveError.TooLarge
            is ReadException.Malformed -> ArchiveError.Malformed
            is ReadException.UnsupportedFormat -> ArchiveError.Unsupported
            is ReadException.Cancelled -> ArchiveError.Cancelled
            else -> ArchiveError.Io
        }
        return ArchiveException(reason, message ?: reason.name)
    }

    private fun NativeArchiveProgress.toModel(phase: ArchiveProgress.Phase) = ArchiveProgress(
        phase = phase,
        bytesDone = bytesDone.toLong(),
        bytesTotal = bytesTotal.toLong(),
        entriesDone = entriesDone.toInt(),
        entriesTotal = entriesTotal.toInt(),
    )

    companion object {
        /** Matches the engine's per-call cap; larger selections must be split by the caller. */
        const val MAX_BATCH_ENTRIES = 20_000
        const val POLL_MILLIS = 120L
        const val COPY_BUFFER = 64 * 1024
        const val OPEN_FOLDER = "archive-open"
        const val PROVIDER_SUFFIX = ".archivecache"

        /** Same rules as the engine: no empty, `.`, `..`, backslash or drive parts. */
        fun safeRelative(path: String): List<String>? {
            if (path.isEmpty() || path.contains('\\') || path.contains(':') || path.contains('\u0000')) return null
            val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) return null
            return parts
        }
    }
}
