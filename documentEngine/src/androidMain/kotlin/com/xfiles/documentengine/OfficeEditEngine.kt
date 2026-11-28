package com.xfiles.documentengine

import com.xfiles.engine.ffi.NativeOfficeEditor
import com.xfiles.engine.ffi.NativeOfficeQuery
import com.xfiles.engine.ffi.NativeSearchState
import com.xfiles.engine.ffi.ReadException
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * A search request. Matching is literal, and any run of whitespace in [text]
 * matches any run of whitespace or line break or tab in the document.
 * [wholeWord] requires non-word characters on both sides. [includeNumberCells]
 * also searches ODS cells that hold numbers or dates; their stored value is
 * not changed by a replacement.
 */
data class OfficeFindQuery(
    val text: String,
    val matchCase: Boolean,
    val wholeWord: Boolean,
    val includeNumberCells: Boolean = false,
)

/** Progress of the native match index. [total] is a lower bound when [truncated]. */
data class OfficeSearchState(
    val total: Int,
    val scannedParts: Int,
    val totalParts: Int,
    val done: Boolean,
    val truncated: Boolean,
)

data class OfficeReplaceReport(val total: Int, val replaced: Int)

/** A failed native operation. [limit] is true when the file exceeds an engine limit. */
class OfficeEditException(message: String, val limit: Boolean = false, val unsupported: Boolean = false) :
    RuntimeException(message)

/**
 * One find and replace session on a document. The session follows the working
 * copy: after a replacement it refers to the edited file, and its match index
 * stays valid for the same query. Blocking: call from an IO lane. [cancel] may
 * be called from any thread and stops the Rust work at its next checkpoint.
 */
class OfficeEditSession(source: File) : AutoCloseable {
    private val editor = NativeOfficeEditor(source.absolutePath)

    /** Prepares the index for [query]. Returns at once; [advanceSearch] does the scanning. */
    fun startSearch(query: OfficeFindQuery): OfficeSearchState = translate {
        editor.startSearch(query.toNative()).toState()
    }

    /** Indexes up to [maxParts] more parts. Each call is one bounded native step. */
    fun advanceSearch(maxParts: Int): OfficeSearchState = translate {
        editor.advanceSearch(maxParts.coerceAtLeast(1).toUInt()).toState()
    }

    /** Context of match [index]. An O(1) lookup in the native index. */
    fun snippet(index: Int): String = translate { editor.searchSnippet(index.coerceAtLeast(0).toUInt()) }

    /**
     * Writes an edited copy to [target], which must be a different file, and
     * makes it the session's working copy. With [onlyIndex] set only that match
     * is replaced. When nothing is replaced the target is not left behind.
     */
    fun replace(target: File, query: OfficeFindQuery, replacement: String, onlyIndex: Int?): OfficeReplaceReport =
        translate {
            val result = editor.replace(
                target.absolutePath,
                query.toNative(),
                replacement,
                onlyIndex?.coerceAtLeast(0)?.toUInt(),
            )
            OfficeReplaceReport(result.total.toInt(), result.replaced.toInt())
        }

    fun cancel() = editor.cancel()

    override fun close() = editor.close()

    private fun OfficeFindQuery.toNative() = NativeOfficeQuery(text, matchCase, wholeWord, includeNumberCells)

    private fun NativeSearchState.toState() = OfficeSearchState(
        total = total.toInt(),
        scannedParts = scannedParts.toInt(),
        totalParts = totalParts.toInt(),
        done = done,
        truncated = truncated,
    )

    private inline fun <T> translate(block: () -> T): T = try {
        block()
    } catch (error: ReadException.Cancelled) {
        throw CancellationException(error.detail)
    } catch (error: ReadException.LimitExceeded) {
        throw OfficeEditException(error.detail, limit = true)
    } catch (error: ReadException.UnsupportedFormat) {
        throw OfficeEditException(error.detail, unsupported = true)
    } catch (error: ReadException) {
        throw OfficeEditException(error.message ?: "The document could not be edited.")
    }
}
