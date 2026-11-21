package com.xfiles.feature.reader

import android.net.Uri
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.OfficeDocumentEditor
import com.xfiles.documentengine.EditValue
import com.xfiles.documentengine.OfficeEditException
import com.xfiles.documents.structure.ContentBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the reader shows about the structured edit session. */
data class StructuredEditState(
    /** Frozen panes per sheet title, read once per open. */
    val freeze: Map<String, SheetFreeze> = emptyMap(),
    /** True when the working copy holds edits that the original does not. */
    val edited: Boolean = false,
    val message: String? = null,
)

/**
 * Structured editing for the open spreadsheet or presentation. The document is staged once; every edit
 * writes the working copy, and the edited preview replaces the open one through [show]. The original
 * file is never written: [saveCopy] writes the working copy where the user picks.
 *
 * Only one document is bound at a time. [bind] discards the previous staged copy.
 */
class StructuredEditSession(
    private val editor: OfficeDocumentEditor,
    private val scope: CoroutineScope,
    private val show: (FilePreview) -> Unit,
) {
    private enum class Kind { Cells, Slides }

    private val mutable = MutableStateFlow(StructuredEditState())
    val state: StateFlow<StructuredEditState> = mutable.asStateFlow()

    private var kind: Kind? = null
    private var copy: OfficeDocumentEditor.WorkingCopy? = null
    private var bindJob: Job? = null

    /** Cell editor for an open XLSX or ODS workbook; null for other documents. */
    val cellEditor: CellEditor? get() = if (kind == Kind.Cells) cells else null

    /** Slide tools for an open PPTX; null for other documents. */
    val slideEditor: SlideEditor? get() = if (kind == Kind.Slides) slides else null

    /**
     * Stages [name] (read from [uri]) for editing, if its format has an editor, and reads the frozen panes of
     * each named sheet. [preview] supplies the sheet titles.
     */
    fun bind(uri: Uri, name: String, preview: FilePreview.Structured) {
        close()
        val next = when (name.substringAfterLast('.', "").lowercase()) {
            "xlsx", "xlsm", "ods" -> Kind.Cells
            "pptx", "pptm" -> Kind.Slides
            else -> return
        }
        kind = next
        val titles = preview.document.blocks.filterIsInstance<ContentBlock.Section>().map { it.title }.filter { it.isNotBlank() }
        bindJob = scope.launch {
            try {
                val staged = editor.stage(uri, name)
                copy = staged
                if (next == Kind.Cells) {
                    val panes = LinkedHashMap<String, SheetFreeze>()
                    for (title in titles.distinct()) {
                        val pane = editor.readPane(staged, title)
                        panes[title] = SheetFreeze(rows = pane.rows, columns = pane.columns)
                    }
                    mutable.update { it.copy(freeze = panes) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                kind = null
                mutable.update { it.copy(message = error.message ?: "This file could not be opened for editing.") }
            }
        }
    }

    /** Writes the working copy to [destination]. */
    fun saveCopy(destination: Uri) {
        val staged = copy ?: return
        scope.launch {
            try {
                editor.saveCopy(staged, destination)
                mutable.update { it.copy(message = "Copy saved.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(message = "The copy could not be saved.") }
            }
        }
    }

    fun clearMessage() = mutable.update { it.copy(message = null) }

    /** Forgets the open document and deletes its workspace copies. */
    fun close() {
        val previous = copy
        bindJob?.cancel()
        bindJob = null
        copy = null
        kind = null
        mutable.value = StructuredEditState()
        if (previous != null) scope.launch { editor.discard(previous) }
    }

    private fun staged(): OfficeDocumentEditor.WorkingCopy =
        copy ?: throw OfficeEditException("The file is still opening. Try again in a moment.")

    private fun publish(preview: FilePreview.Structured) {
        show(preview)
        mutable.update { it.copy(edited = true, message = null) }
    }

    private val cells = object : CellEditor {
        override suspend fun read(sheet: String, row: Int, column: Int): RawCell {
            val stored = editor.readCell(staged(), sheet, row, column)
            return RawCell(kind = stored.kind, raw = stored.raw)
        }

        override suspend fun save(sheet: String, row: Int, column: Int, value: CellValue) {
            val native = when (value) {
                is CellValue.Text -> EditValue.Text(value.text)
                is CellValue.Number -> EditValue.Number(value.value)
            }
            publish(editor.editCell(staged(), sheet, row, column, native))
        }
    }

    private val slides = object : SlideEditor {
        override suspend fun reorder(keep: List<Int>): Int {
            val preview = editor.reorderSlides(staged(), keep)
            publish(preview)
            return slideCount(preview)
        }

        override suspend fun delete(index: Int): Int {
            val preview = editor.deleteSlide(staged(), index)
            publish(preview)
            return slideCount(preview)
        }

        override suspend fun duplicate(index: Int): Int {
            val preview = editor.duplicateSlide(staged(), index)
            publish(preview)
            return slideCount(preview)
        }
    }

    private fun slideCount(preview: FilePreview.Structured): Int =
        preview.document.blocks.count { it is ContentBlock.Slide }
}

/** The MIME type for a "Save a copy" dialog, from the file name's extension. */
fun structuredMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "xlsx", "xlsm" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
    "pptx", "pptm" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    else -> "application/octet-stream"
}
