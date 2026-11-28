package com.xfiles.feature.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xfiles.core.documents.FileProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

internal class ViewerProgress(val store: FileProgress, val file: String) {
    fun read(field: String): String? = store.read(file, field)
    fun save(field: String, value: String) = store.write(file, field, value)
    fun number(field: String, fallback: Int = 0): Int = read(field)?.toIntOrNull() ?: fallback
}

internal val LocalViewerProgress = compositionLocalOf<ViewerProgress?> { null }

@Composable
internal fun SaveViewerProgress(field: String, read: () -> String?) {
    val progress = LocalViewerProgress.current
    val latest by rememberUpdatedState(read)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(progress, field) {
        if (progress == null) return@LaunchedEffect
        snapshotFlow { latest() }.conflate().collect { value ->
            if (value != null) progress.save(field, value)
            delay(750)
        }
    }
    DisposableEffect(progress, field, lifecycle) {
        fun flush() { latest()?.let { progress?.save(field, it) } }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) flush() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            flush()
        }
    }
}

@Composable
internal fun rememberViewerList(field: String, itemCount: Int): LazyListState {
    val progress = LocalViewerProgress.current
    val saved = remember(progress, field) { progress?.read(field)?.split(':').orEmpty() }
    val index = (saved.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    val offset = (saved.getOrNull(1)?.toIntOrNull() ?: 0).coerceAtLeast(0)
    val state = rememberLazyListState(index, offset)
    SaveViewerProgress(field) {
        if (state.layoutInfo.totalItemsCount == 0) null
        else "${state.firstVisibleItemIndex}:${state.firstVisibleItemScrollOffset}"
    }
    return state
}

@Composable
internal fun rememberViewerScroll(field: String): androidx.compose.foundation.ScrollState {
    val progress = LocalViewerProgress.current
    val initial = remember(progress, field) { progress?.number(field)?.coerceAtLeast(0) ?: 0 }
    val state = rememberScrollState(initial)
    SaveViewerProgress(field) { state.value.toString().takeIf { state.maxValue != Int.MAX_VALUE } }
    return state
}
