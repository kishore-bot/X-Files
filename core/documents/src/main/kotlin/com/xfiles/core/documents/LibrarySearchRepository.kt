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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
class LibrarySearchRepository @Inject constructor(@ApplicationContext context: Context) {
    private val indexPath = File(context.noBackupFilesDir, "library-index").absolutePath
    private val analyzer = DocumentAnalyzer(context.contentResolver)
    private val indexing = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex()

    suspend fun search(
        query: String,
        files: List<FileEntry>,
        onResults: (LibraryContentSearch) -> Unit,
    ) = withContext(indexing) {
        lock.withLock {
            val candidates = files.filter {
                it.kind in INDEXABLE_KINDS && it.sizeBytes?.let { size -> size <= MAX_INPUT_BYTES } != false
            }.distinctBy { it.uri }
            val byKey = candidates.associateBy { it.uri.toString() }
            val entries = candidates.map { file ->
                IndexedDocument(file.uri.toString(), file.sizeBytes ?: 0, file.modifiedAt ?: 0)
            }
            DocumentTextIndex(indexPath).use { index ->
                index.retain(entries)
                val changed = index.stale(entries)
                var count = entries.size - changed.size
                fun publish(indexing: Boolean) {
                    val hits = index.query(query).mapNotNull { hit ->
                        byKey[hit.key]?.let { LibrarySearchHit(it, hit.snippet, hit.matches) }
                    }
                    onResults(LibraryContentSearch(query, hits, indexing, count, entries.size))
                }
                publish(changed.isNotEmpty())
                try {
                    for (position in changed) {
                        currentCoroutineContext().ensureActive()
                        if (!index.hasRoom()) break
                        val file = candidates[position]
                        val text = try {
                            analyzer.extractText(file.uri, file.name, 64_000u)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            ""
                        }
                        currentCoroutineContext().ensureActive()
                        if (!index.add(entries[position], text)) break
                        count++
                        if (count % SAVE_EVERY == 0) {
                            index.save()
                            publish(indexing = true)
                        }
                        delay(15)
                    }
                    publish(indexing = false)
                } finally {
                    try {
                        index.save()
                    } catch (error: Exception) {
                        Log.w("XfilesLibrary", "Could not save the search index", error)
                    }
                }
            }
        }
    }

    /**
     * For Fily: files whose already-indexed text matches [query]. Only reads the index, never indexes, so a
     * voice answer never waits on a library scan; while a scan holds the index it returns nothing.
     */
    suspend fun indexedMatches(query: String, files: List<FileEntry>, limit: Int): List<FileEntry> = withContext(indexing) {
        if (query.isBlank() || !lock.tryLock()) return@withContext emptyList()
        try {
            val byKey = files.associateBy { it.uri.toString() }
            DocumentTextIndex(indexPath).use { index ->
                index.query(query).asSequence().mapNotNull { byKey[it.key] }.distinctBy { it.uri }.take(limit).toList()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("XfilesLibrary", "Content search for Fily failed", error)
            emptyList()
        } finally {
            lock.unlock()
        }
    }

    suspend fun clear() = withContext(indexing) {
        lock.withLock {
            DocumentTextIndex(indexPath).use { it.clear() }
        }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 32L * 1024 * 1024
        const val SAVE_EVERY = 8
        // PDFs too: the Rust engine reads their text layer (invoices and contracts are mostly PDFs).
        val INDEXABLE_KINDS = setOf(DocumentKind.Text, DocumentKind.Document, DocumentKind.Pdf)
    }
}
