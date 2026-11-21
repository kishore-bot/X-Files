package com.xfiles.feature.reader

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the reader shows for pictures: one picture, or a gallery session to page through. */
sealed interface GalleryUiState {
    data object Off : GalleryUiState

    /**
     * [seed] is the picture the reader already decoded for [seedUri]; that page shows it without a second decode.
     * A session of one picture renders exactly as before the gallery existed.
     */
    data class Paged(val session: GallerySession, val seed: Bitmap?, val seedUri: String?) : GalleryUiState
}

/**
 * Owns the gallery of the open picture. It follows the reader state rather than being called from [open]: a
 * picture that opens starts a gallery; a page turn only moves the reader's current file, so the session is never
 * rebuilt. Everything here runs on the caller's (main) dispatcher; the work that touches storage is in
 * [GalleryCatalog] and [GalleryImages].
 *
 * The sort order is held here and saved by [GallerySortStore]. Changing it re-sorts the candidates already listed
 * and keeps the current picture selected: the session is rebuilt around that picture, so no listing is repeated.
 */
class GalleryController @Inject constructor(
    private val catalog: GalleryCatalog,
    private val images: GalleryImages,
    private val sortStore: GallerySortStore,
) {
    private val mutableState = MutableStateFlow<GalleryUiState>(GalleryUiState.Off)
    val state: StateFlow<GalleryUiState> = mutableState.asStateFlow()
    /** The gallery sort, as held and persisted by [sortStore]. */
    val sort: StateFlow<GallerySort> = sortStore.sort
    private var reader: MutableStateFlow<ReaderState?>? = null
    private var buildJob: Job? = null
    /** The pictures listed for the open gallery, in listing order; sessions are built from these. */
    private var candidates: List<FileEntry> = emptyList()

    /** Starts observing [reader]. Call once, before the reader restores a document. */
    fun attach(scope: CoroutineScope, reader: MutableStateFlow<ReaderState?>) {
        this.reader = reader
        // The stored sort arrives after the first read on the IO lane; an open gallery re-sorts when it does.
        scope.launch {
            sortStore.sort.collect { sort -> if (mutableState.value is GalleryUiState.Paged) rebuild(sort) }
        }
        scope.launch {
            reader.collect { current ->
                val ready = current as? ReaderState.Ready
                val picture = ready?.preview as? FilePreview.Picture
                when {
                    current == null -> {
                        stop()
                        images.clear()
                    }
                    ready == null || picture == null -> stop()
                    // A page turn of this gallery, or a copy of the same state: nothing to rebuild.
                    (mutableState.value as? GalleryUiState.Paged)?.session?.contains(ready.file.uri.toString()) == true -> Unit
                    else -> start(scope, ready.file, picture.bitmap)
                }
            }
        }
    }

    /**
     * Moves the gallery and the reader to [index]. Returns the newly current file, or null when there is no
     * gallery to move in. Decodes outside the new neighbourhood are cancelled.
     */
    fun select(index: Int): FileEntry? {
        val paged = mutableState.value as? GalleryUiState.Paged ?: return null
        if (index !in paged.session.entries.indices) return null
        val next = paged.session.select(index)
        if (next === paged.session) return next.current
        mutableState.value = paged.copy(session = next)
        retainNeighbourhood(next)
        val ready = reader?.value as? ReaderState.Ready ?: return next.current
        reader?.value = ready.copy(file = next.current)
        return next.current
    }

    /** Saves [sort]; the open gallery re-sorts through [attach]'s collector and keeps the current picture selected. */
    fun setSort(sort: GallerySort) {
        if (sort != sortStore.sort.value) sortStore.save(sort)
    }

    /** The animation of a single (non-gallery) picture, sized to [maxEdge]; null for a still. */
    suspend fun animationOf(file: FileEntry, maxEdge: Int): AnimatedImageDrawable? = images.animation(file, maxEdge)

    /** The screen-sized decode of page [index], shared with any in-flight prefetch. Throws when the file cannot be decoded. */
    suspend fun bitmap(index: Int, maxEdge: Int): Bitmap? {
        val session = (mutableState.value as? GalleryUiState.Paged)?.session ?: return null
        val entry = session.entries.getOrNull(index) ?: return null
        return try {
            images.load(entry, maxEdge)
        } catch (cancelled: CancellationException) {
            // The window cancelled this decode, not the page: the page is still on screen, so ask once more.
            currentCoroutineContext().ensureActive()
            images.load(entry, maxEdge)
        }
    }

    /** The animation of page [index] for a GIF, WebP or AVIF; null for stills. The caller plays it while the page settles. */
    suspend fun animation(index: Int, maxEdge: Int): AnimatedImageDrawable? {
        val session = (mutableState.value as? GalleryUiState.Paged)?.session ?: return null
        val entry = session.entries.getOrNull(index) ?: return null
        return images.animation(entry, maxEdge)
    }

    private fun start(scope: CoroutineScope, opened: FileEntry, seed: Bitmap) {
        buildJob?.cancel()
        images.retain(emptySet())
        candidates = emptyList()
        val uri = opened.uri.toString()
        mutableState.value = GalleryUiState.Paged(GallerySession.single(opened), seed, uri)
        buildJob = scope.launch {
            catalog.listings(opened).collect { listing ->
                // Ignore a listing that finishes after the reader has moved on to another picture.
                val ready = reader?.value as? ReaderState.Ready ?: return@collect
                if (ready.file.uri != opened.uri) return@collect
                candidates = listing
                rebuild()
            }
        }
    }

    /** Rebuilds the session around the current picture from [candidates]; the picture stays selected. */
    private fun rebuild(sort: GallerySort = sortStore.sort.value) {
        val paged = mutableState.value as? GalleryUiState.Paged ?: return
        val session = GallerySession.create(paged.session.current, candidates, sort)
        mutableState.value = paged.copy(session = session)
        retainNeighbourhood(session)
    }

    private fun stop() {
        buildJob?.cancel()
        buildJob = null
        candidates = emptyList()
        images.retain(emptySet())
        if (mutableState.value != GalleryUiState.Off) mutableState.value = GalleryUiState.Off
    }

    /** Keeps decodes for the current picture and its two neighbours; the pager prefetches exactly these. */
    private fun retainNeighbourhood(session: GallerySession) {
        val keep = HashSet<String>(3)
        for (position in session.index - 1..session.index + 1) {
            session.entries.getOrNull(position)?.let { keep += it.uri.toString() }
        }
        images.retain(keep)
    }
}
