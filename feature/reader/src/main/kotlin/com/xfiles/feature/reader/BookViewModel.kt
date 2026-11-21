package com.xfiles.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.documents.book.BookAnnotations
import com.xfiles.documents.book.BookNote
import com.xfiles.documents.book.Highlight
import com.xfiles.documents.book.HighlightColor
import com.xfiles.documents.book.LruCache
import com.xfiles.documents.book.ReadingSettings
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
}

/** The part of a highlight in one block: the range of its text and the text itself. */
data class HighlightPiece(val block: Int, val range: IntRange, val quote: String)

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
 * Holds one open book's annotations, settings and sheet. Every change is applied to the state at
 * once, and written to disk after a short quiet period, so a burst of edits or scrolling costs one
 * write. The pagination cache lives here so a settings change and back costs no re-measuring.
 */
@HiltViewModel
class BookViewModel @Inject constructor(private val store: BookStore) : ViewModel() {
    private val mutableState = MutableStateFlow(BookUiState())
    val state: StateFlow<BookUiState> = mutableState.asStateFlow()

    /**
     * Paginations of recent layouts, so returning to a layout is instant. Keyed without the theme,
     * so a colour change reuses the same pages. Touched on the main thread only.
     */
    internal val layouts = LruCache<BookLayoutKey, BookLayout>(capacity = LAYOUT_CACHE_SIZE)

    private var saveJob: Job? = null
    private var settingsJob: Job? = null

    /** Writes still pending when the ViewModel goes away, run outside its scope so they finish. */
    private val leftover = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Loads [key]'s annotations and the global reading settings. Calling it again for the open book does nothing. */
    fun open(key: String) {
        if (mutableState.value.key == key) return
        viewModelScope.launch {
            val defaults = store.loadSettings()
            val annotations = store.loadAnnotations(key)
            mutableState.update { BookUiState(key = key, defaults = defaults, annotations = annotations, sheet = it.sheet) }
        }
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
    }
}
