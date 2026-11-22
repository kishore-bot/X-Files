package com.xfiles.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import java.util.ServiceLoader

/**
 * Draws a sheet's grid. The reader has a built-in grid; a build can supply another renderer through
 * [java.util.ServiceLoader] (a `META-INF/services` entry naming an implementation with a public no-arg
 * constructor). With none present, the built-in grid is used.
 */
interface SheetGridRenderer {
    @Composable
    fun Grid(spec: SheetGridSpec, modifier: Modifier)
}

/**
 * Everything a renderer needs. Rows and columns are 0-based sheet positions. The first [frozenRows] rows
 * and [frozenColumns] columns stay pinned; the column letters and row numbers are drawn by [columnLabel]
 * and [rowLabel], and [corner] fills the space where they meet.
 */
@Immutable
class SheetGridSpec(
    val rowCount: Int,
    val columnCount: Int,
    val frozenRows: Int,
    val frozenColumns: Int,
    val columnWidth: Dp,
    val rowHeight: Dp,
    /** Rows taller than [rowHeight], such as wrapped text. */
    val rowHeights: Map<Int, Dp>,
    val labelWidth: Dp,
    val labelHeight: Dp,
    val corner: @Composable () -> Unit,
    val columnLabel: @Composable (column: Int) -> Unit,
    val rowLabel: @Composable (row: Int) -> Unit,
    val cell: @Composable (row: Int, column: Int) -> Unit,
    /** Called with the grid's scroller once it is on screen, and with null when it leaves. */
    val onScroller: (SheetGridScroller?) -> Unit,
)

/** Scrolling the grid on request, for the voice assistant. Distances are in pixels. */
interface SheetGridScroller {
    /** The visible body height in pixels. */
    val viewportHeight: Int

    suspend fun scrollBy(dy: Float)

    suspend fun scrollToTop()

    suspend fun scrollToBottom()
}

/** The supplied renderer, looked up once; null keeps the built-in grid. */
internal object SheetGrids {
    val renderer: SheetGridRenderer? by lazy {
        try {
            ServiceLoader.load(SheetGridRenderer::class.java, SheetGridRenderer::class.java.classLoader).firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }
}

/** Adapts a [SheetGridScroller] to the assistant's scroll commands. */
internal class SheetGridViewerScroll(private val scroller: SheetGridScroller) : ViewerScroll {
    override val operations: Set<String> = SCROLL_OPERATIONS

    override suspend fun scroll(operation: String): Boolean {
        when (operation) {
            "scroll_down" -> scroller.scrollBy(scroller.viewportHeight * PAGE_FRACTION)
            "scroll_up" -> scroller.scrollBy(-scroller.viewportHeight * PAGE_FRACTION)
            "scroll_top" -> scroller.scrollToTop()
            "scroll_bottom" -> scroller.scrollToBottom()
            else -> return false
        }
        return true
    }

    private companion object {
        const val PAGE_FRACTION = 0.8f
    }
}
