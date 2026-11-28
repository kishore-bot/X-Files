package com.xfiles.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.xfiles.settings.ThemePreference

/**
 * Shows the launcher icon that matches the theme preference. The manifest declares three launcher aliases of
 * MainActivity (Auto, Light, Dark); exactly one is enabled. Changing the enabled alias is a PackageManager call,
 * so it runs off the main thread (see AppearanceStore) and is skipped when the alias is already right.
 */
internal object LauncherIcons {
    private val aliases = mapOf(
        ThemePreference.System to "LauncherAuto",
        ThemePreference.Light to "LauncherLight",
        ThemePreference.Dark to "LauncherDark",
    )

    fun apply(context: Context, theme: ThemePreference) {
        val manager = context.packageManager
        // Alias names are relative to the Kotlin package of MainActivity, not to the applicationId (dev builds differ).
        val namespace = MainActivity::class.java.name.substringBeforeLast('.')
        val chosen = aliases.getValue(theme)
        for (alias in aliases.values) {
            val component = ComponentName(context.packageName, "$namespace.$alias")
            val wanted = if (alias == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (manager.getComponentEnabledSetting(component) == wanted) continue
            manager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
        }
    }
}
