package com.xfiles.android

import android.content.Context
import com.xfiles.settings.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
internal class AppearanceStore @Inject constructor(@ApplicationContext context: Context) {
    private val settings = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableTheme = MutableStateFlow(
        ThemePreference.entries.find { it.name == settings.getString("theme", null) } ?: ThemePreference.System,
    )
    private val mutableHaptics = MutableStateFlow(settings.getBoolean("haptics", true))
    val theme = mutableTheme.asStateFlow()
    val haptics = mutableHaptics.asStateFlow()

    fun setTheme(theme: ThemePreference) {
        settings.edit().putString("theme", theme.name).apply()
        mutableTheme.value = theme
    }

    fun setHaptics(enabled: Boolean) {
        settings.edit().putBoolean("haptics", enabled).apply()
        mutableHaptics.value = enabled
    }
}
