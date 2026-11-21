package com.xfiles.feature.reader

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The gallery sort order, remembered across pictures and launches. [sort] is Name until the stored choice is read
 * on the IO lane; SharedPreferences is never touched on the main thread (it is opened and read there too). Writes
 * use the same single IO lane, so they keep the order they were made in.
 */
@Singleton
class GallerySortStore @Inject constructor(@ApplicationContext context: Context) {
    private val appContext = context
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val preferences: SharedPreferences by lazy { appContext.getSharedPreferences("gallery", Context.MODE_PRIVATE) }
    private val mutableSort = MutableStateFlow(GallerySort.Name)
    val sort: StateFlow<GallerySort> = mutableSort.asStateFlow()

    /** Set by [save]; a stored choice read late must not overwrite a choice the user already made. */
    @Volatile private var chosen = false

    init {
        scope.launch {
            val stored = preferences.getString(KEY, null)
            if (!chosen) mutableSort.value = GallerySort.entries.firstOrNull { it.name == stored } ?: GallerySort.Name
        }
    }

    /** Takes effect at once for [sort] collectors; the write to disk is applied asynchronously on the IO lane. */
    fun save(sort: GallerySort) {
        chosen = true
        mutableSort.value = sort
        scope.launch { preferences.edit().putString(KEY, sort.name).apply() }
    }

    private companion object {
        const val KEY = "sort"
    }
}
