package com.xfiles.documentengine

import com.xfiles.engine.ffi.NativeCellEdit
import com.xfiles.engine.ffi.NativeCellValue
import com.xfiles.engine.ffi.ReadException
import com.xfiles.engine.ffi.pptxDeleteSlide
import com.xfiles.engine.ffi.pptxDuplicateSlide
import com.xfiles.engine.ffi.pptxReorderSlides
import com.xfiles.engine.ffi.xlsxApplyCellEdits
import com.xfiles.engine.ffi.xlsxReadCell
import com.xfiles.engine.ffi.xlsxReadFreeze
import java.io.File

/** Frozen rows and columns of a sheet's pane. Zero means not frozen. */
data class SheetPane(val rows: Int, val columns: Int)

/** Stored value of a cell. [kind] is "Empty", "Text", "Number", "Bool", "Error" or "Formula". */
data class StoredCell(val kind: String, val raw: String)

/** A value to store. Rows and columns are 0-based. */
sealed interface EditValue {
    data class Text(val text: String) : EditValue

    data class Number(val value: Double) : EditValue
}

/**
 * Blocking calls into the engine's XLSX cell editor and PPTX slide tools. Call from an IO lane only.
 * Every write produces a new file at [target]; [source] is never modified. The engine re-opens each
 * copy before returning, and a failed write leaves no file behind. Failures are [OfficeEditException].
 */
object SheetSlideEdits {
    fun readPane(source: File, sheet: String): SheetPane = native {
        val pane = xlsxReadFreeze(source.absolutePath, sheet)
        SheetPane(rows = pane.rows.toInt(), columns = pane.columns.toInt())
    }

    fun readCell(source: File, sheet: String, row: Int, column: Int): StoredCell = native {
        val cell = xlsxReadCell(source.absolutePath, sheet, row.toUInt(), column.toUInt())
        StoredCell(kind = cell.kind.name, raw = cell.raw)
    }

    /** Writes one cell into a copy. Returns the number of edits applied (always 1 here). */
    fun saveCell(source: File, target: File, sheet: String, row: Int, column: Int, value: EditValue): Int = native {
        val native = when (value) {
            is EditValue.Text -> NativeCellValue.Text(value.text)
            is EditValue.Number -> NativeCellValue.Number(value.value)
        }
        xlsxApplyCellEdits(
            source.absolutePath,
            target.absolutePath,
            listOf(NativeCellEdit(sheet = sheet, row = row.toUInt(), column = column.toUInt(), value = native)),
        ).toInt()
    }

    /** Writes a copy whose slides follow [keep] (original indices, in the new order). Returns the slide count. */
    fun reorderSlides(source: File, target: File, keep: List<Int>): Int = native {
        pptxReorderSlides(source.absolutePath, target.absolutePath, keep.map { it.toUInt() }).toInt()
    }

    fun deleteSlide(source: File, target: File, index: Int): Int = native {
        pptxDeleteSlide(source.absolutePath, target.absolutePath, index.toUInt()).toInt()
    }

    fun duplicateSlide(source: File, target: File, index: Int): Int = native {
        pptxDuplicateSlide(source.absolutePath, target.absolutePath, index.toUInt()).toInt()
    }

    private inline fun <T> native(block: () -> T): T = try {
        block()
    } catch (error: ReadException.LimitExceeded) {
        throw OfficeEditException(error.detail, limit = true)
    } catch (error: ReadException.UnsupportedFormat) {
        throw OfficeEditException(error.detail, unsupported = true)
    } catch (error: ReadException) {
        throw OfficeEditException(error.message ?: "The file could not be changed.")
    }
}
