package com.xfiles.feature.reader

import androidx.compose.foundation.gestures.animateScrollBy
import android.webkit.WebView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/** Scroll operations a viewer can take for the voice assistant; the names match its controlReader catalog. */
internal val SCROLL_OPERATIONS = setOf("scroll_down", "scroll_up", "scroll_top", "scroll_bottom")

/**
 * A viewer that scrolls when the assistant asks. Viewers register while on screen; the assistant reads
 * [operations] at request time and sends one of them to [scroll].
 */
interface ViewerScroll {
    /** The scroll operations this viewer takes, a subset of [SCROLL_OPERATIONS]. */
    val operations: Set<String>

    /** Performs [operation] on the main thread. Returns false when the viewer did not take it. */
    suspend fun scroll(operation: String): Boolean
}

/** Registers a [ViewerScroll] while its viewer is composed; the returned function unregisters it. */
internal val LocalViewerScroll = staticCompositionLocalOf<(ViewerScroll) -> () -> Unit> { { {} } }

/** Holds the WebView of a page so the assistant can page through it. */
internal class WebViewHandle {
    var view: WebView? = null
}

/** Scrolls a web page by its own pages. Works with scripts off: WebView paging needs no JavaScript. */
internal class WebViewScroll(private val handle: WebViewHandle) : ViewerScroll {
    override val operations: Set<String> = SCROLL_OPERATIONS

    override suspend fun scroll(operation: String): Boolean {
        val view = handle.view ?: return false
        when (operation) {
            "scroll_down" -> view.pageDown(false)
            "scroll_up" -> view.pageUp(false)
            "scroll_top" -> view.pageUp(true)
            "scroll_bottom" -> view.pageDown(true)
            else -> return false
        }
        return true
    }
}

/** Scrolls a lazy list by most of a screen; top and bottom jump to the first and last item. */
internal class LazyScroll(private val state: LazyListState) : ViewerScroll {
    override val operations: Set<String> = SCROLL_OPERATIONS

    override suspend fun scroll(operation: String): Boolean {
        val page = state.layoutInfo.viewportSize.height * PAGE_FRACTION
        when (operation) {
            "scroll_down" -> state.animateScrollBy(page)
            "scroll_up" -> state.animateScrollBy(-page)
            "scroll_top" -> state.scrollToItem(0)
            "scroll_bottom" -> state.scrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            else -> return false
        }
        return true
    }

    private companion object {
        const val PAGE_FRACTION = 0.8f
    }
}

/** Scrolls a plain scrolling column by most of a screen. */
internal class ColumnScroll(private val state: ScrollState) : ViewerScroll {
    override val operations: Set<String> = SCROLL_OPERATIONS

    override suspend fun scroll(operation: String): Boolean {
        val page = state.viewportSize * 0.8f
        when (operation) {
            "scroll_down" -> state.animateScrollBy(page)
            "scroll_up" -> state.animateScrollBy(-page)
            "scroll_top" -> state.animateScrollTo(0)
            "scroll_bottom" -> state.animateScrollTo(state.maxValue)
            else -> return false
        }
        return true
    }
}

/** Paged content (book pages, slides): scrolling down or up turns one page; top and bottom jump to the ends. */
internal class PagerScroll(private val state: PagerState) : ViewerScroll {
    override val operations: Set<String> = SCROLL_OPERATIONS

    override suspend fun scroll(operation: String): Boolean {
        val last = (state.pageCount - 1).coerceAtLeast(0)
        val target = when (operation) {
            "scroll_down" -> state.currentPage + 1
            "scroll_up" -> state.currentPage - 1
            "scroll_top" -> 0
            "scroll_bottom" -> last
            else -> return false
        }.coerceIn(0, last)
        state.animateScrollToPage(target)
        return true
    }
}

/** Offers [scroll] to the voice assistant while the calling viewer is on screen. */
@Composable
internal fun RegisterViewerScroll(key: Any, scroll: () -> ViewerScroll) {
    val register = LocalViewerScroll.current
    DisposableEffect(key, register) {
        val unregister = register(scroll())
        onDispose { unregister() }
    }
}
