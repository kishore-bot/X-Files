package com.xfiles.android

import android.content.Context
import com.xfiles.settings.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
internal class AppearanceStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val settings = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    // One lane, so rapid theme changes apply in order and the last choice wins.
    private val launcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutableTheme = MutableStateFlow(
        ThemePreference.entries.find { it.name == settings.getString("theme", null) } ?: ThemePreference.System,
    )
    private val mutableHaptics = MutableStateFlow(settings.getBoolean("haptics", true))
    val theme = mutableTheme.asStateFlow()
    val haptics = mutableHaptics.asStateFlow()

    init {
        // Keeps the launcher icon in step with the saved theme, even after an update or a restored backup.
        val saved = mutableTheme.value
        launcherScope.launch { LauncherIcons.apply(context, saved) }
    }

    fun setTheme(theme: ThemePreference) {
        settings.edit().putString("theme", theme.name).apply()
        mutableTheme.value = theme
        launcherScope.launch { LauncherIcons.apply(context, theme) }
    }

    fun setHaptics(enabled: Boolean) {
        settings.edit().putBoolean("haptics", enabled).apply()
        mutableHaptics.value = enabled
    }
}
