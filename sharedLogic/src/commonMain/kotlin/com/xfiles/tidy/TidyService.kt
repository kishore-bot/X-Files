package com.xfiles.tidy

import com.xfiles.documents.DocumentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Runs Tidy up scans over the library's folders and keeps their results.
 *
 * Kotlin only orchestrates: it lists files, opens descriptors, asks [library] for text and [files] for
 * small grey previews, and hands everything to [engine] (Rust) for hashing, fingerprints, grouping and
 * classification. Work runs on [io], one file at a time, so memory stays bounded and the UI is never
 * starved; Rust's hashing runs on the same thread through blocking FFI calls. Nothing starts until the
 * user opens Tidy up.
 *
 * What each file yielded is cached by identity, size and modification time, so a rescan only reads new
 * or changed files; the last report is cached too, so reopening the screen is instant.
 */
class TidyService(
    private val library: TidyLibrary,
    private val files: TidyFiles,
    private val engine: TidyEngine,
    private val store: TidyStore,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val limits: TidyLimits = TidyLimits(),
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val lock = Mutex()
    private var job: Job? = null
    private var loaded = false
    private var facts: MutableMap<String, FileFacts> = mutableMapOf()

    private val mutableReport = MutableStateFlow<TidyReport?>(null)
    private val mutableProgress = MutableStateFlow<TidyProgress?>(null)
    private val mutableFailure = MutableStateFlow<String?>(null)

    /** The last finished scan, from this session or the cache. */
    val report: StateFlow<TidyReport?> = mutableReport.asStateFlow()

    /** The running scan's step, or `null` when none is running. */
    val progress: StateFlow<TidyProgress?> = mutableProgress.asStateFlow()

    /** Why the last scan stopped early, if it failed. */
    val failure: StateFlow<String?> = mutableFailure.asStateFlow()

    val canDelete: Boolean get() = files.canDelete

    /** Loads the cached report, once. Cheap to call again. */
    suspend fun load() {
        lock.withLock {
            if (loaded) return
            withContext(io) { loadCache() }
            loaded = true
        }
    }

    /** Starts a scan unless one is running. */
    fun scan() {
        if (job?.isActive == true) return
        mutableFailure.value = null
        mutableProgress.value = TidyProgress(TidyPhase.Listing)
        job = scope.launch {
            try {
                load()
                val report = run()
                mutableReport.value = report
                saveCache(report)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableFailure.value = "The scan stopped: ${error.message ?: "something went wrong"}."
            } finally {
                mutableProgress.value = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Deletes an extra copy the user just confirmed and takes it out of the results. */
    suspend fun delete(file: TidyFile): Boolean = lock.withLock {
        if (!files.canDelete || mutableProgress.value != null) return@withLock false
        val group = mutableReport.value?.identical?.firstOrNull { group -> group.files.drop(1).any { it.identity == file.identity } }
            ?: return@withLock false
        val deleted = withContext(io) { files.deleteDuplicate(file, group.files.first()) }
        if (!deleted) return@withLock false
        mutableReport.value?.without(file)?.let { updated ->
            mutableReport.value = updated
            withContext(io) { saveCache(updated) }
        }
        true
    }

    private suspend fun run(): TidyReport {
        val all = library.files()
        currentCoroutineContext().ensureActive()
        val seen = HashMap<String, FileFacts>(all.size)
        fun factsOf(file: TidyFile): FileFacts {
            val key = keyOf(file)
            return seen.getOrPut(key) { facts[key]?.copy() ?: FileFacts(readVersion = FACTS_VERSION) }
        }

        // 1. Identical files, all types.
        val hashable = all.filter { (it.sizeBytes ?: 0) > 0 }
        mutableProgress.value = TidyProgress(TidyPhase.Identical, 0, hashable.size)
        val scanJob = currentCoroutineContext().job
        val hashes = engine.findIdentical(
            hashable.map { file -> factsOf(file).let { HashInput(file.sizeBytes ?: 0, it.partial, it.full) } },
            object : HashSource {
                override fun open(index: Int): Int? = files.openDescriptor(hashable[index])
                override fun onProgress(done: Int, total: Int) {
                    mutableProgress.value = TidyProgress(TidyPhase.Identical, done, total)
                }
                override fun isCancelled(): Boolean = !scanJob.isActive
            },
        )
        hashable.forEachIndexed { i, file ->
            val f = factsOf(file)
            f.partial = hashes.partial.getOrNull(i)
            f.full = hashes.full.getOrNull(i)
        }
        val identical = hashes.groups.map { group ->
            val members = group.map { hashable[it] }
            IdenticalGroup(members, members.first().sizeBytes ?: 0)
        }
        // Extra copies add nothing to "similar": one member of each identical group stands for it.
        val extras = identical.asSequence().flatMap { it.files.drop(1) }.map { it.identity }.toSet()

        // 2. Documents: one text pass gives the fingerprint and the collection.
        val documents = all.asSequence()
            .filter { isTextual(it) && (it.sizeBytes ?: 0) <= limits.maxTextFileBytes }
            .take(limits.maxDocuments).toList()
        documents.forEachIndexed { i, file ->
            currentCoroutineContext().ensureActive()
            mutableProgress.value = TidyProgress(TidyPhase.Documents, i, documents.size)
            val f = factsOf(file)
            if (!f.textRead) {
                val text = try {
                    library.plainText(file, limits.maxTextChars)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (text.isNullOrBlank()) {
                    f.simhash = null
                    f.collection = engine.classifyName(file.name)?.let(::StoredMatch)
                } else {
                    val result = engine.analyzeText(file.name, text)
                    f.simhash = result.fingerprint
                    f.collection = result.collection?.let(::StoredMatch)
                }
                // A provider failure is retried on the next explicit scan.
                f.textRead = !text.isNullOrBlank()
            }
            yield()
        }

        // 3. Photos: a 32x32 grey preview each, fingerprinted in Rust.
        val photos = all.asSequence().filter { it.kind == DocumentKind.Image }.take(limits.maxPhotos).toList()
        photos.forEachIndexed { i, file ->
            currentCoroutineContext().ensureActive()
            mutableProgress.value = TidyProgress(TidyPhase.Photos, i, photos.size)
            val f = factsOf(file)
            if (!f.imageRead) {
                val preview = try {
                    files.grayThumbnail(file, THUMBNAIL_SIDE)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                f.dhash = preview?.let { engine.imageFingerprint(it.width, it.height, it.pixels) }
                f.collection = engine.classifyName(file.name)?.let(::StoredMatch)
                f.imageRead = preview != null
            }
            yield()
        }

        // 4. Grouping, all in Rust.
        mutableProgress.value = TidyProgress(TidyPhase.Grouping)
        val similarDocuments = similar(documents.filter { it.identity !in extras }, images = false) { factsOf(it).simhash }
        val similarPhotos = similar(photos.filter { it.identity !in extras }, images = true) { factsOf(it).dhash }
        // Filename rules need no content read, including files beyond the read budgets.
        val candidates = all.filter { isTextual(it) || it.kind == DocumentKind.Image }
        candidates.forEach { file ->
            currentCoroutineContext().ensureActive()
            val f = factsOf(file)
            if (f.collection == null) f.collection = engine.classifyName(file.name)?.let(::StoredMatch)
        }
        val collections = candidates.asSequence()
            .mapNotNull { file -> factsOf(file).collection?.let { file to it } }
            .groupBy { it.second.kind }
            .map { (kind, entries) -> TidyCollection(kind, entries.map { (file, m) -> CollectionEntry(file, m.reason()) }) }
            .sortedBy { it.kind.ordinal }

        facts = seen
        return TidyReport(clock(), all.size, identical, similarDocuments, similarPhotos, collections)
    }

    private fun similar(candidates: List<TidyFile>, images: Boolean, fingerprint: (TidyFile) -> Long?): List<SimilarGroup> {
        val printed = candidates.mapNotNull { file -> fingerprint(file)?.let { file to it } }
        if (printed.size < 2) return emptyList()
        return engine.groupSimilar(printed.map { it.second }, images).map { group -> SimilarGroup(group.map { printed[it].first }) }
    }

    private fun isTextual(file: TidyFile): Boolean =
        file.kind in setOf(DocumentKind.Text, DocumentKind.Pdf, DocumentKind.Document) && !file.name.endsWith(".zip", ignoreCase = true)

    private fun loadCache() {
        try {
            store.get(FACTS_KEY)?.let { stored -> facts = json.decodeFromString<Map<String, FileFacts>>(stored).toMutableMap() }
            facts.values.forEach { f ->
                if (f.readVersion < FACTS_VERSION) {
                    f.textRead = false
                    f.collection = null
                    f.readVersion = FACTS_VERSION
                }
            }
            store.get(REPORT_KEY)?.let { stored -> mutableReport.value = json.decodeFromString<StoredReport>(stored).toDomain() }
        } catch (_: SerializationException) {
            // From another version, or damaged: the next scan rebuilds it.
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun saveCache(report: TidyReport) {
        store.put(REPORT_KEY, json.encodeToString(StoredReport.of(report)))
        store.put(FACTS_KEY, json.encodeToString<Map<String, FileFacts>>(facts))
    }

    private fun keyOf(file: TidyFile) = "${file.identity}|${file.sizeBytes}|${file.modifiedAt}"

    private companion object {
        const val REPORT_KEY = "tidy_report_v1"
        const val FACTS_KEY = "tidy_facts_v1"
        const val FACTS_VERSION = 2
        const val THUMBNAIL_SIDE = 32
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Whether an open document shows a card, ID or bank account number, for the reader's "Hide from
 * screenshots" hint. Only three flags per file are kept, keyed by identity, size and modification time.
 */
class SensitiveCheck(
    private val library: TidyLibrary,
    private val engine: TidyEngine,
    private val store: TidyStore,
    private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private var cache: LinkedHashMap<String, StoredFindings>? = null

    /** `null` if the file has no readable text. */
    suspend fun check(file: TidyFile): SensitiveFindings? {
        val key = file.sizeBytes?.let { size -> file.modifiedAt?.let { "${file.identity}|$size|$it" } }
        if (key != null) lock.withLock { cached()[key] }?.let { return it.toDomain() }
        val text = try {
            library.plainText(file, MAX_CHARS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val found = withContext(io) { engine.sensitive(text) }
        if (key != null) {
            lock.withLock {
                val map = cached()
                map.remove(key)
                map[key] = StoredFindings(found.paymentCard, found.nationalId, found.bankAccount)
                while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
                val snapshot = map.toMap()
                withContext(io) { store.put(KEY, json.encodeToString(snapshot)) }
            }
        }
        return found
    }

    private suspend fun cached(): LinkedHashMap<String, StoredFindings> = cache ?: withContext(io) {
        try {
            store.get(KEY)?.let { LinkedHashMap(json.decodeFromString<Map<String, StoredFindings>>(it)) }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: LinkedHashMap()
    }.also { cache = it }

    @Serializable
    private data class StoredFindings(val card: Boolean = false, val id: Boolean = false, val bank: Boolean = false) {
        fun toDomain() = SensitiveFindings(card, id, bank)
    }

    private companion object {
        const val KEY = "sensitive_v1"
        const val MAX_CHARS = 100_000
        const val MAX_ENTRIES = 400
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Budgets for one scan, sized for a 2 GB phone. */
data class TidyLimits(
    val maxTextChars: Int = 60_000,
    val maxTextFileBytes: Long = 64L * 1024 * 1024,
    val maxDocuments: Int = 2_000,
    val maxPhotos: Int = 3_000,
)

@Serializable
internal data class FileFacts(
    var partial: Long? = null,
    var full: Long? = null,
    var simhash: Long? = null,
    var dhash: Long? = null,
    var collection: StoredMatch? = null,
    var textRead: Boolean = false,
    var imageRead: Boolean = false,
    var readVersion: Int = 0,
)

@Serializable
internal data class StoredMatch(
    val kind: CollectionKind,
    val terms: List<String> = emptyList(),
    val nameTerms: List<String> = emptyList(),
    val amounts: Int = 0,
    val dates: Int = 0,
) {
    constructor(match: CollectionMatch) : this(match.kind, match.reason.terms, match.reason.nameTerms, match.reason.amounts, match.reason.dates)

    fun reason() = CollectionReason(terms, nameTerms, amounts, dates)
}

@Serializable
private data class StoredFile(
    val uri: String,
    val name: String,
    val kind: DocumentKind,
    val size: Long? = null,
    val modified: Long? = null,
    val identity: String? = null,
) {
    fun toDomain() = TidyFile(uri, name, kind, size, modified, identity ?: uri)

    companion object {
        fun of(file: TidyFile) = StoredFile(file.uri, file.name, file.kind, file.sizeBytes, file.modifiedAt, file.identity.takeIf { it != file.uri })
    }
}

@Serializable
private data class StoredEntry(val file: StoredFile, val match: StoredMatch)

@Serializable
private data class StoredReport(
    val scannedAt: Long,
    val fileCount: Int,
    val identical: List<List<StoredFile>>,
    val similarDocuments: List<List<StoredFile>>,
    val similarPhotos: List<List<StoredFile>>,
    val collections: List<StoredEntry>,
) {
    fun toDomain() = TidyReport(
        scannedAt = scannedAt,
        fileCount = fileCount,
        identical = identical.map { group -> group.map { it.toDomain() }.let { IdenticalGroup(it, it.first().sizeBytes ?: 0) } },
        similarDocuments = similarDocuments.map { group -> SimilarGroup(group.map { it.toDomain() }) },
        similarPhotos = similarPhotos.map { group -> SimilarGroup(group.map { it.toDomain() }) },
        collections = collections.groupBy { it.match.kind }
            .map { (kind, entries) -> TidyCollection(kind, entries.map { CollectionEntry(it.file.toDomain(), it.match.reason()) }) }
            .sortedBy { it.kind.ordinal },
    )

    companion object {
        fun of(report: TidyReport) = StoredReport(
            scannedAt = report.scannedAt,
            fileCount = report.fileCount,
            identical = report.identical.map { group -> group.files.map(StoredFile::of) },
            similarDocuments = report.similarDocuments.map { group -> group.files.map(StoredFile::of) },
            similarPhotos = report.similarPhotos.map { group -> group.files.map(StoredFile::of) },
            collections = report.collections.flatMap { collection ->
                collection.entries.map { StoredEntry(StoredFile.of(it.file), StoredMatch(collection.kind, it.reason.terms, it.reason.nameTerms, it.reason.amounts, it.reason.dates)) }
            },
        )
    }
}
