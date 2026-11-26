package com.xfiles.feature.editor

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.core.documents.DocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plain-text editor session. The text is LF-only in [text]; [EditorState] carries the file's encoding
 * and line ending, which the user can change before a save. Encoding and line-ending work runs on
 * Dispatchers.Default, and the save writes bytes through the repository on its IO dispatcher.
 */
@HiltViewModel
class TextEditorViewModel @Inject constructor(private val documents: DocumentRepository) : ViewModel() {
    val text = TextFieldState()
    private val mutableState = MutableStateFlow<EditorState?>(null)
    val state = mutableState.asStateFlow()
    private var baseline = ""
    private var baselineFormat = TextEncoding.Utf8 to LineEnding.Lf
    private var loadJob: Job? = null

    /** Opens text that the caller already decoded (UTF-8, LF). */
    fun open(name: String, source: String) {
        if (mutableState.value?.saving == true) return
        loadJob?.cancel()
        baselineFormat = TextEncoding.Utf8 to LineEnding.Lf
        val overCap = source.toByteArray(Charsets.UTF_8).size > TextCodec.EDIT_CAP_BYTES
        baseline = if (overCap) "" else source
        text.setTextAndPlaceCursorAtEnd(baseline)
        mutableState.value = EditorState(name, blocked = if (overCap) OVER_CAP else null)
    }

    /**
     * Opens a file from its bytes: the encoding and line endings are detected, and the editor keeps
     * them for the save. Files over [TextCodec.EDIT_CAP_BYTES] or with mixed line endings open read-only.
     * The caller reads at most EDIT_CAP_BYTES + 1 bytes, so a large file never loads in full.
     */
    fun openBytes(name: String, bytes: ByteArray) {
        if (mutableState.value?.saving == true) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { decode(name, bytes) }
    }

    private suspend fun decode(name: String, bytes: ByteArray) {
        if (bytes.size > TextCodec.EDIT_CAP_BYTES) {
            openBlocked(name, OVER_CAP)
            return
        }
        val decoded = withContext(Dispatchers.Default) { TextCodec.decode(bytes) }
        baseline = decoded.text
        baselineFormat = decoded.encoding to decoded.lineEnding
        text.setTextAndPlaceCursorAtEnd(decoded.text)
        mutableState.value = EditorState(
            name = name,
            encoding = decoded.encoding,
            lineEnding = decoded.lineEnding,
            blocked = if (decoded.mixedLineEndings) MIXED_LINE_ENDINGS else null,
        )
    }

    private fun openBlocked(name: String, reason: String) {
        baseline = ""
        baselineFormat = TextEncoding.Utf8 to LineEnding.Lf
        text.setTextAndPlaceCursorAtEnd("")
        mutableState.value = EditorState(name, blocked = reason)
    }

    /**
     * Opens the file at [uri] with its detected encoding and line endings. Reads at most the cap plus one
     * byte on the IO dispatcher, so a large file is refused without being loaded whole.
     */
    fun openFile(name: String, uri: Uri) {
        if (mutableState.value?.saving == true) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val bytes = try {
                documents.readBoundedBytes(uri, TextCodec.EDIT_CAP_BYTES + 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesEditor", "Could not read file for editing", error)
                openBlocked(name, READ_FAILED)
                return@launch
            }
            decode(name, bytes)
        }
    }

    fun chooseEncoding(encoding: TextEncoding) = updateState { it.copy(encoding = encoding, saved = false) }

    fun chooseLineEnding(lineEnding: LineEnding) = updateState { it.copy(lineEnding = lineEnding, saved = false) }

    fun hasChanges(): Boolean {
        val current = mutableState.value ?: return false
        return text.text.toString() != baseline || (current.encoding to current.lineEnding) != baselineFormat
    }

    fun close() {
        if (mutableState.value?.saving == true) return
        loadJob?.cancel()
        mutableState.value = null
        baseline = ""
        text.setTextAndPlaceCursorAtEnd("")
    }

    /**
     * Selects the next match of [query] after the cursor, wrapping to the start; returns the number of
     * matches (0 when none). Case-insensitive, like the reader's find.
     */
    fun findNext(query: String): Int {
        if (query.isEmpty()) return 0
        val content = text.text.toString()
        val count = occurrences(content, query).count()
        if (count == 0) return 0
        val from = text.selection.max
        val at = content.indexOf(query, from, ignoreCase = true).takeIf { it >= 0 } ?: content.indexOf(query, ignoreCase = true)
        text.edit { selection = TextRange(at, at + query.length) }
        return count
    }

    /** Replaces the selected match (if the selection is one), then moves to the next. */
    fun replaceNext(query: String, replacement: String): Int {
        if (query.isEmpty()) return 0
        val selected = text.text.substring(text.selection.min, text.selection.max)
        if (selected.equals(query, ignoreCase = true)) {
            val start = text.selection.min
            text.edit {
                replace(start, start + query.length, replacement)
                selection = TextRange(start + replacement.length)
            }
        }
        return findNext(query)
    }

    /** Replaces every match in one edit, so a single undo restores them all; returns how many. */
    fun replaceAll(query: String, replacement: String): Int {
        if (query.isEmpty()) return 0
        val content = text.text.toString()
        val matches = occurrences(content, query).toList()
        if (matches.isEmpty()) return 0
        text.edit {
            // Back to front, so earlier offsets stay valid.
            matches.asReversed().forEach { start -> replace(start, start + query.length, replacement) }
        }
        return matches.size
    }

    fun undo() = text.undoState.undo()
    fun redo() = text.undoState.redo()

    private fun occurrences(content: String, query: String): Sequence<Int> =
        generateSequence(content.indexOf(query, ignoreCase = true).takeIf { it >= 0 }) { previous ->
            content.indexOf(query, previous + query.length, ignoreCase = true).takeIf { it >= 0 }
        }

    fun suggestFileName(): String {
        val name = mutableState.value?.name ?: "Note.txt"
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "txt")
        return if (name == "Note.txt") name else "$stem - copy.$extension"
    }

    /** Writes the text in the chosen encoding and line ending to a new copy at [uri]. */
    fun save(uri: Uri) {
        val current = mutableState.value ?: return
        if (current.saving || current.blocked != null) return
        loadJob?.cancel()
        val content = text.text.toString()
        mutableState.value = current.copy(saving = true, saved = false, error = null)
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { TextCodec.encode(content, current.encoding, current.lineEnding) }
                documents.saveBytesCopy(uri, bytes)
                baseline = content
                baselineFormat = current.encoding to current.lineEnding
                updateState { it.copy(saving = false, saved = true, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TextEncodingException) {
                updateState { it.copy(saving = false, saved = false, error = error.message) }
            } catch (error: Exception) {
                Log.w("XfilesEditor", "Could not save text copy", error)
                updateState { it.copy(saving = false, saved = false, error = COPY_FAILED) }
            }
        }
    }

    private inline fun updateState(transform: (EditorState) -> EditorState) {
        mutableState.value?.let { mutableState.value = transform(it) }
    }

    companion object {
        const val OVER_CAP = "This file is larger than the 256 KiB editing limit, so it stays read-only here. Use Save As in another app for large files."
        const val MIXED_LINE_ENDINGS = "This file mixes line endings. Saving would change some of them, so it stays read-only here."
        const val COPY_FAILED = "The copy could not be saved."
        const val READ_FAILED = "The file could not be opened for editing."
    }
}

/**
 * Editor state. [blocked] explains why editing is off (the text stays visible). [error] is a message
 * from the last save, or null.
 */
data class EditorState(
    val name: String,
    val encoding: TextEncoding = TextEncoding.Utf8,
    val lineEnding: LineEnding = LineEnding.Lf,
    val blocked: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)
