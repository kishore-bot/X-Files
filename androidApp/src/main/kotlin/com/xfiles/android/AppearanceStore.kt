package com.xfiles.android

import android.app.UiModeManager
import android.content.Context
import android.os.Build
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
        applySystemNightMode(saved)
    }

    /**
     * Tells Android the app's own light/dark choice (Android 12+), so the system splash screen on the next launch
     * matches the app instead of the phone's setting. The manifest handles uiMode changes, so nothing restarts.
     */
    private fun applySystemNightMode(theme: ThemePreference) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = context.getSystemService(UiModeManager::class.java) ?: return
        val mode = when (theme) {
            ThemePreference.System -> UiModeManager.MODE_NIGHT_AUTO
            ThemePreference.Light -> UiModeManager.MODE_NIGHT_NO
            ThemePreference.Dark -> UiModeManager.MODE_NIGHT_YES
        }
        manager.setApplicationNightMode(mode)
    }

    /**
     * Switches the theme in place: the UI animates from the new state and no activity is recreated. Enabling a
     * different launcher alias while the task is on screen makes many launchers relaunch the app (the "flash" or
     * close), so the icon swap waits until Xfiles leaves the foreground ([applyPendingLauncherIcon]).
     */
    fun setTheme(theme: ThemePreference) {
        settings.edit().putString("theme", theme.name).apply()
        mutableTheme.value = theme
        pendingLauncherTheme = theme
    }

    /** Called from MainActivity.onStop, so the launcher icon follows the theme once the app is in the background. */
    fun applyPendingLauncherIcon() {
        val theme = pendingLauncherTheme ?: return
        pendingLauncherTheme = null
        applySystemNightMode(theme)
        launcherScope.launch { LauncherIcons.apply(context, theme) }
    }

    @Volatile private var pendingLauncherTheme: ThemePreference? = null

    fun setHaptics(enabled: Boolean) {
        settings.edit().putBoolean("haptics", enabled).apply()
        mutableHaptics.value = enabled
    }
}
