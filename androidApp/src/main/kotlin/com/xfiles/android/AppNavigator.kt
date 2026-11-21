package com.xfiles.android

import android.net.Uri
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
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

    fun openDocument(uri: Uri, page: Int = 0) {
        analysis.open(uri, page)
        // An external open replaces whatever sat above the library; it never stacks readers.
        while (stack.size > 1 && stack.last() != Route.Library) stack.removeAt(stack.lastIndex)
        stack.add(Route.Reader)
    }

    fun openEditor(name: String, text: String) {
        editor.open(name, text)
        stack.remove(Route.Editor)
        stack.add(Route.Editor)
    }

    /** Opens a file's bytes in the editor, so its encoding and line endings are detected on read. */
    fun openEditorFile(uri: Uri, name: String) {
        editor.openFile(name, uri)
        stack.remove(Route.Editor)
        stack.add(Route.Editor)
    }

    fun show(route: Route) {
        stack.remove(route)
        stack.add(route)
    }

    /** System back. The reader closes its session; other screens just leave. */
    fun back() {
        when (stack.lastOrNull()) {
            Route.Reader -> analysis.close()
            Route.Editor -> editor.close()
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
