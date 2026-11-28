package com.xfiles.android

import android.net.Uri
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.xfiles.feature.editor.EditorOpenRequest
import com.xfiles.feature.editor.TextEditorViewModel
import com.xfiles.feature.reader.DocumentAnalysisViewModel

/**
 * Owns the back stack and keeps it in step with the sessions: the reader and editor ViewModels hold
 * the open document and draft, and their routes exist only while that session does.
 */
internal class AppNavigator(
    private val stack: NavBackStack<NavKey>,
    private val analysis: DocumentAnalysisViewModel,
    private val editor: TextEditorViewModel,
) {
    val top: NavKey? get() = stack.lastOrNull()

    fun openDocument(uri: Uri, page: Int? = null) {
        analysis.open(uri, page)
        // An external open replaces whatever sat above the library; it never stacks readers.
        val keepEditor = editor.hasChanges() || editor.state.value?.saving == true
        for (index in stack.lastIndex downTo 1) {
            if (stack[index] != Route.Editor || !keepEditor) stack.removeAt(index)
        }
        show(Route.Reader)
    }

    val editorRequestCount: Int get() = editor.incomingCount

    fun openEditor(name: String, text: String) {
        requestEditorDraft(name, text)
    }

    fun requestEditorDraft(name: String, text: String): Boolean = requestEditor(EditorOpenRequest.Draft(name, text))

    fun openEditorFile(uri: Uri, name: String) {
        requestEditor(EditorOpenRequest.File(uri, name))
    }

    private fun requestEditor(request: EditorOpenRequest): Boolean {
        if (editorRequestCount == 0 && editor.state.value?.saving != true && !editor.hasChanges()) {
            openEditorRequest(request)
            return true
        }
        editor.queueOpen(request)
        return false
    }

    fun resolveEditorRequest(replace: Boolean) {
        if (editor.state.value?.saving == true) return
        val request = editor.nextOpen() ?: return
        if (replace) openEditorRequest(request)
    }

    private fun openEditorRequest(request: EditorOpenRequest) {
        when (request) {
            is EditorOpenRequest.Draft -> editor.open(request.name, request.text)
            is EditorOpenRequest.File -> editor.openFile(request.name, request.uri)
        }
        show(Route.Editor)
    }

    fun show(route: Route) {
        stack.remove(route)
        stack.add(route)
    }

    /** System back. The reader closes its session; other screens just leave. */
    fun back() {
        when (stack.lastOrNull()) {
            Route.Reader -> analysis.close()
            Route.Editor -> {
                if (editor.hasChanges() || editor.state.value?.saving == true) return
                editor.close()
            }
            else -> Unit
        }
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /**
     * Follows the sessions: adds a route when a session starts elsewhere (Fily or a tool opening a file),
     * and drops one whose session ended inside its screen or did not survive process death.
     */
    fun sync(readerOpen: Boolean, editorOpen: Boolean) {
        if (readerOpen && Route.Reader !in stack) stack.add(Route.Reader)
        if (!readerOpen) stack.remove(Route.Reader)
        if (editorOpen && Route.Editor !in stack) stack.add(Route.Editor)
        if (!editorOpen) stack.remove(Route.Editor)
        if (stack.isEmpty()) stack.add(Route.Library)
    }
}
