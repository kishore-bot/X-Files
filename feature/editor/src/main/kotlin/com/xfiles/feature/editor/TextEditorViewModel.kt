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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TextEditorViewModel @Inject constructor(private val documents: DocumentRepository) : ViewModel() {
    val text = TextFieldState()
    private val mutableState = MutableStateFlow<EditorState?>(null)
    val state = mutableState.asStateFlow()
    private var baseline = ""

    fun open(name: String, source: String) {
        if (mutableState.value?.saving == true) return
        baseline = source
        text.setTextAndPlaceCursorAtEnd(source)
        mutableState.value = EditorState(name)
    }

    fun hasChanges(): Boolean = text.text.toString() != baseline

    fun close() {
        if (mutableState.value?.saving == true) return
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

    fun save(uri: Uri) {
        val current = mutableState.value ?: return
        if (current.saving) return
        val content = text.text.toString()
        mutableState.value = current.copy(saving = true, saved = false, error = false)
        viewModelScope.launch {
            try {
                documents.saveTextCopy(uri, content)
                baseline = content
                mutableState.value = current.copy(saving = false, saved = true, error = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("XfilesEditor", "Could not save text copy", error)
                mutableState.value = current.copy(saving = false, saved = false, error = true)
            }
        }
    }
}

data class EditorState(
    val name: String,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: Boolean = false,
)
