package com.xfiles.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.documents.book.BookAnnotations
import com.xfiles.documents.book.BookNote
import com.xfiles.documents.book.Highlight
import com.xfiles.documents.book.HighlightColor
import com.xfiles.documents.book.LruCache
import com.xfiles.documents.book.ReadingSettings
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.feature.reader.domain.BookSearchHit
import com.xfiles.feature.reader.domain.BookSearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which bottom sheet of the reader is open. Each carries only what the sheet needs. */
sealed interface BookSheet {
    data object None : BookSheet

    data object Contents : BookSheet

    data object Settings : BookSheet

    data object Notebook : BookSheet

    data class Footnote(val label: String, val text: String) : BookSheet

    /** A note being written on the text [quote], which starts at [range] of [block]. */
    data class NewNote(val block: Int, val range: IntRange, val quote: String) : BookSheet

    /** A highlight or note the reader tapped. */
    data class Marked(val block: Int, val highlightId: String?, val noteId: String?) : BookSheet

    /** The text of the note [noteId] being edited. */
    data class EditNote(val noteId: String, val quote: String, val text: String) : BookSheet

    /** A link out of the book, shown for confirmation before it opens. */
    data class ExternalLink(val url: String) : BookSheet

    /** Find text in the book. The query and hits live in [BookUiState.search]. */
    data object Search : BookSheet

    /** A picture of the book, opened full screen from a tap on it. */
    data class Picture(val image: ContentBlock.Image) : BookSheet
}

/** The part of a highlight in one block: the range of its text and the text itself. */
data class HighlightPiece(val block: Int, val range: IntRange, val quote: String)

/** The find-in-book state: the text asked for, and where it was found so far. */
data class BookSearchState(
    val query: String = "",
    val hits: List<BookSearchHit> = emptyList(),
    val searching: Boolean = false,
)

/** What the reader's screen can ask of its ViewModel. The newer features go through here. */
sealed interface BookEvent {
    data class SetMode(val mode: BookReadingMode) : BookEvent

    /** Shows the chrome (bars) when hidden, hides it when shown. */
    data object ToggleChrome : BookEvent

    data class Search(val blocks: List<ContentBlock>, val query: String) : BookEvent

    data object ClearSearch : BookEvent

    data class OpenExternal(val url: String) : BookEvent
}

/**
 * Immutable UI state of the reader. [key] is null until the book's annotations are loaded.
 * [defaults] are the global reading settings; a book with its own [BookAnnotations.settings] reads with those.
 */
data class BookUiState(
    val key: String? = null,
    val defaults: ReadingSettings = ReadingSettings(),
    val annotations: BookAnnotations = BookAnnotations(),
    val sheet: BookSheet = BookSheet.None,
    /** Blocks the reader left by an internal link, most recent last; system back returns to them. */
    val backStack: List<Int> = emptyList(),
    /** Paged or scroll; global to every book. */
    val mode: BookReadingMode = BookReadingMode.Default,
    /** Whether the top and bottom bars are shown. Hidden on open, as in the EzFiles reader; a centre tap toggles them. */
    val chromeVisible: Boolean = false,
    val search: BookSearchState = BookSearchState(),
) {
    val loaded: Boolean
        get() = key != null

    /** The settings the book is read with. */
    val settings: ReadingSettings
        get() = annotations.settings ?: defaults

    /** Whether this book has settings of its own rather than the global ones. */
    val usesOwnSettings: Boolean
        get() = annotations.settings != null
}

/**
 * Holds one open book's annotations, settings, mode and sheet. Every change is applied to the state at
 * once, and written to disk after a short quiet period, so a burst of edits or scrolling costs one
 * write. The pagination cache lives here so a settings change and back costs no re-measuring.
 */
@HiltViewModel
class BookViewModel @Inject constructor(
    private val store: BookStore,
    private val finder: BookSearchUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BookUiState())
    val state: StateFlow<BookUiState> = mutableState.asStateFlow()

    /**
     * Paginations of recent layouts, so returning to a layout is instant. Keyed without the theme,
     * so a colour change reuses the same pages. Touched on the main thread only.
     */
    internal val layouts = LruCache<BookLayoutKey, BookLayout>(capacity = LAYOUT_CACHE_SIZE)

    private var saveJob: Job? = null
    private var settingsJob: Job? = null
    private var searchJob: Job? = null

    /** Writes still pending when the ViewModel goes away, run outside its scope so they finish. */
    private val leftover = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Loads [key]'s annotations, the global settings and the reading mode. Calling it again for the open book does nothing. */
    fun open(key: String) {
        if (mutableState.value.key == key) return
        viewModelScope.launch {
            val defaults = store.loadSettings()
            val annotations = store.loadAnnotations(key)
            val mode = store.loadReadingMode()
            mutableState.update {
                BookUiState(key = key, defaults = defaults, annotations = annotations, sheet = it.sheet, mode = mode)
            }
        }
    }

    /** Handles a request from the reader screen. */
    fun onEvent(event: BookEvent) {
        when (event) {
            is BookEvent.SetMode -> setMode(event.mode)
            BookEvent.ToggleChrome -> mutableState.update { it.copy(chromeVisible = !it.chromeVisible) }
            is BookEvent.Search -> search(event.blocks, event.query)
            BookEvent.ClearSearch -> clearSearch()
            is BookEvent.OpenExternal -> showSheet(BookSheet.ExternalLink(event.url))
        }
    }

    /** Changes the reading mode for every book, and writes it to the store. */
    fun setMode(mode: BookReadingMode) {
        if (mutableState.value.mode == mode) return
        mutableState.update { it.copy(mode = mode) }
        viewModelScope.launch { store.saveReadingMode(mode) }
    }

    /**
     * Changes the settings being edited: the book's own when it has them, else the global ones.
     * A change to the global settings is written to the settings file; a book's goes into its own file.
     */
    fun setSettings(settings: ReadingSettings) {
        val sanitized = settings.sanitized()
        val current = mutableState.value
        if (current.key != null && current.usesOwnSettings) {
            edit { it.withSettings(sanitized) }
            return
        }
        mutableState.update { it.copy(defaults = sanitized) }
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            delay(SETTINGS_SAVE_DELAY_MS)
            store.saveSettings(sanitized)
        }
    }

    /** Gives the book its own copy of the current settings ([own] = true), or drops them so it follows the global ones. */
    fun setBookSettings(own: Boolean) {
        val current = mutableState.value
        if (current.key == null || current.usesOwnSettings == own) return
        val copy = if (own) current.settings else null
        edit { it.withSettings(copy) }
    }

    /** Puts the settings being edited back to the app's defaults. */
    fun resetSettings() = setSettings(ReadingSettings())

    fun showSheet(sheet: BookSheet) {
        mutableState.update { it.copy(sheet = sheet) }
    }

    fun dismissSheet() = showSheet(BookSheet.None)

    /**
     * Finds [query] in [blocks]. The search waits a moment for typing to settle, then runs off the
     * main thread; a stale result for an older query is dropped.
     */
    fun search(blocks: List<ContentBlock>, query: String) {
        searchJob?.cancel()
        mutableState.update { it.copy(search = BookSearchState(query = query, searching = query.isNotBlank())) }
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val hits = withContext(Dispatchers.Default) { finder(blocks, query) }
            mutableState.update {
                if (it.search.query == query) it.copy(search = BookSearchState(query, hits, searching = false)) else it
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        mutableState.update { it.copy(search = BookSearchState()) }
    }

    /** Remembers the block the reader is at. Frequent, so written after a longer pause. */
    fun savePosition(block: Int) {
        val current = mutableState.value
        val key = current.key ?: return
        if (current.annotations.position == block) return
        mutableState.update { it.copy(annotations = it.annotations.withPosition(block)) }
        scheduleSave(key, POSITION_SAVE_DELAY_MS)
    }

    /** Records that the reader left [block] by a link, so system back can return there. Not saved. */
    fun pushJump(block: Int) {
        mutableState.update { it.copy(backStack = (it.backStack + block).takeLast(MAX_JUMPS)) }
    }

    /** Takes the block the last link was left from, or null when there is none. */
    fun popJump(): Int? {
        val state = mutableState.value
        val block = state.backStack.lastOrNull() ?: return null
        mutableState.value = state.copy(backStack = state.backStack.dropLast(1))
        return block
    }

    /** Highlights each piece of a selection in [color]; one range keeps one colour. */
    fun addHighlights(pieces: List<HighlightPiece>, color: HighlightColor) {
        if (pieces.isEmpty()) return
        edit { annotations ->
            pieces.fold(annotations) { acc, piece ->
                acc.withHighlight(
                    Highlight(
                        newId(), piece.block, piece.range.first, piece.range.last + 1,
                        color, BookAnnotations.clip(piece.quote), now(),
                    ),
                )
            }
        }
    }

    /** Changes the colour of the highlight [id]; the range stays the same. */
    fun recolorHighlight(id: String, color: HighlightColor) {
        edit { annotations ->
            annotations.highlights.firstOrNull { it.id == id }
                ?.let { annotations.withHighlight(it.copy(color = color)) }
                ?: annotations
        }
    }

    fun removeHighlight(id: String) = edit { it.withoutHighlight(id) }

    fun addNote(block: Int, range: IntRange, quote: String, text: String) {
        edit { annotations ->
            annotations.withNote(
                BookNote(newId(), block, range.first, range.last + 1, text.take(MAX_NOTE_CHARS), BookAnnotations.clip(quote), now()),
            )
        }
    }

    fun editNote(id: String, text: String) {
        edit { annotations ->
            annotations.notes.firstOrNull { it.id == id }
                ?.let { annotations.withNote(it.copy(text = text.take(MAX_NOTE_CHARS))) }
                ?: annotations
        }
    }

    fun removeNote(id: String) = edit { it.withoutNote(id) }

    fun toggleBookmark(block: Int, label: String) = edit { it.withBookmarkToggled(block, label, newId(), now()) }

    fun removeBookmark(id: String) = edit { it.withoutBookmark(id) }

    private fun edit(transform: (BookAnnotations) -> BookAnnotations) {
        val key = mutableState.value.key ?: return
        mutableState.update { it.copy(annotations = transform(it.annotations)) }
        scheduleSave(key, EDIT_SAVE_DELAY_MS)
    }

    /** Restarts the quiet period; the write, when it comes, takes the newest state. */
    private fun scheduleSave(key: String, delayMillis: Long) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(delayMillis)
            store.saveAnnotations(key, mutableState.value.annotations)
        }
    }

    override fun onCleared() {
        val key = mutableState.value.key
        if (key != null && saveJob?.isActive == true) {
            val snapshot = mutableState.value.annotations
            leftover.launch { store.saveAnnotations(key, snapshot) }
        }
        super.onCleared()
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val LAYOUT_CACHE_SIZE = 3
        const val MAX_JUMPS = 50
        const val MAX_NOTE_CHARS = 2_000
        const val EDIT_SAVE_DELAY_MS = 120L
        const val POSITION_SAVE_DELAY_MS = 800L
        const val SETTINGS_SAVE_DELAY_MS = 300L
        const val SEARCH_DEBOUNCE_MS = 180L
    }
}
