package com.xfiles.core.documents

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.util.Log
import com.xfiles.documentengine.DocumentAnalyzer
import com.xfiles.documentengine.DocumentTextIndex
import com.xfiles.documentengine.IndexedDocument
import com.xfiles.documents.DocumentKind
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LibrarySearchHit(val file: FileEntry, val snippet: String, val matches: List<IntRange>)
data class LibraryContentSearch(
    val query: String = "",
    val hits: List<LibrarySearchHit> = emptyList(),
    val indexing: Boolean = false,
    val indexed: Int = 0,
    val total: Int = 0,
)

@Singleton
class LibrarySearchRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val pdfs: AndroidPdfDocument,
) {
    private val indexPath = File(context.noBackupFilesDir, "library-index-2").absolutePath
    private val analyzer = DocumentAnalyzer(context.contentResolver)
    private val indexing = Dispatchers.IO.limitedParallelism(1)
    private val queryLane = Dispatchers.IO.limitedParallelism(2)
    private val writer = Mutex()
    private val snapshotLock = Mutex()
    private var committed: DocumentTextIndex? = null
    private val status = MutableStateFlow(IndexStatus())

    /** Query the published snapshot whenever a batch commits. Keystrokes never restart extraction. */
    suspend fun search(query: String, files: List<FileEntry>, onResults: (LibraryContentSearch) -> Unit) {
        val byKey = withContext(queryLane) { files.associateBy { it.uri.toString() } }
        var previousRevision = -1L
        var hits = emptyList<LibrarySearchHit>()
        status.collectLatest { current ->
            if (current.revision != previousRevision) {
                hits = queryCommitted(query).mapNotNull { hit ->
                    byKey[hit.key]?.let { LibrarySearchHit(it, hit.snippet, hit.matches) }
                }
                previousRevision = current.revision
            }
            currentCoroutineContext().ensureActive()
            onResults(LibraryContentSearch(query, hits, current.indexing, current.indexed, current.total))
        }
    }

    suspend fun refresh(files: List<FileEntry>, prune: Boolean) = PerformanceTrace.onLane(
        "XfilesIndexRefresh", "XfilesIndexQueue", indexing,
    ) {
        writer.withLock {
            val candidates = files.filter {
                it.kind in INDEXABLE_KINDS && (it.kind != DocumentKind.Pdf || pdfs.supportsIndexText) &&
                    it.sizeBytes?.let { size -> size <= MAX_INPUT_BYTES } != false
            }.distinctBy { it.uri }
            val entries = candidates.map { file ->
                IndexedDocument(file.uri.toString(), file.sizeBytes ?: 0, file.modifiedAt ?: 0)
            }
            // A writer open can reclaim orphans. Serialize it with readers that still hold a snapshot.
            val index = PerformanceTrace.span("XfilesIndexWriterOpen") {
                snapshotLock.withLock { DocumentTextIndex(indexPath) }
            }
            index.use {
                var needsCommit = prune && index.retain(entries) > 0
                val changed = index.stale(entries)
                var count = entries.size - changed.size
                var pending = 0
                status.value = status.value.copy(indexing = changed.isNotEmpty(), indexed = count, total = entries.size)
                suspend fun commit() {
                    if (!needsCommit && pending == 0) return
                    // A saved generation must become visible even when the requesting scan is cancelled.
                    withContext(NonCancellable) {
                        PerformanceTrace.span("XfilesIndexSave") { index.save() }
                        val replacement = PerformanceTrace.span("XfilesIndexSnapshotLoad") { DocumentTextIndex.committed(indexPath) }
                        snapshotLock.withLock {
                            val previous = committed
                            committed = replacement
                            previous?.close()
                            val prior = status.value
                            status.value = IndexStatus(prior.revision + 1, prior.indexing, count, entries.size)
                        }
                    }
                    pending = 0
                    needsCommit = false
                }
                try {
                    for (position in changed) {
                        currentCoroutineContext().ensureActive()
                        val file = candidates[position]
                        val text = try {
                            PerformanceTrace.span("XfilesIndexExtract") {
                                if (file.kind == DocumentKind.Pdf) pdfs.indexText(file.uri, 64_000)
                                else analyzer.extractText(file.uri, file.name, 64_000u)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A temporary failure stays stale. Successful empty content still gets indexed.
                            continue
                        }
                        currentCoroutineContext().ensureActive()
                        if (!index.add(entries[position], text)) continue
                        count++
                        pending++
                        // Surface the first success promptly, then amortize serialization on bigger batches.
                        if (count == 1 || pending >= SAVE_EVERY) commit()
                    }
                    commit()
                } finally {
                    status.value = status.value.copy(indexing = false)
                }
            }
        }
    }

    private suspend fun queryCommitted(query: String, limit: Int = 50) = PerformanceTrace.onLane(
        "XfilesIndexQuery", "XfilesIndexQueryQueue", queryLane,
    ) {
        snapshotLock.withLock {
            val index = committed ?: DocumentTextIndex.committed(indexPath).also { committed = it }
            index.query(query, limit)
        }
    }

    /** Fily reads the same committed index as library search. */
    suspend fun indexedMatches(query: String, files: List<FileEntry>, limit: Int): List<FileEntry> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return try {
            val byKey = withContext(queryLane) { files.associateBy { it.uri.toString() } }
            queryCommitted(query).asSequence().mapNotNull { byKey[it.key] }.distinctBy { it.uri }.take(limit).toList()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("XfilesLibrary", "Content search for Fily failed", error)
            emptyList()
        }
    }

    suspend fun clear() = withContext(indexing) {
        writer.withLock {
            snapshotLock.withLock {
                DocumentTextIndex(indexPath).use { it.clear() }
                committed?.close()
                committed = null
                status.value = IndexStatus(revision = status.value.revision + 1)
            }
        }
    }

    private data class IndexStatus(
        val revision: Long = 0,
        val indexing: Boolean = false,
        val indexed: Int = 0,
        val total: Int = 0,
    )

    private companion object {
        const val MAX_INPUT_BYTES = 32L * 1024 * 1024
        const val SAVE_EVERY = 64
        val INDEXABLE_KINDS = setOf(DocumentKind.Text, DocumentKind.Document, DocumentKind.Pdf)
    }
}
