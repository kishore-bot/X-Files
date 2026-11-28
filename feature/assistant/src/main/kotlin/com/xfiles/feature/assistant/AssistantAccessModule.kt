package com.xfiles.feature.assistant

import android.content.Context
import com.xfiles.fily.AssistantAccess
import com.xfiles.fily.FilyPreferences
import com.xfiles.fily.ReaderControl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * One assistant access policy for the whole process: the Fily overlay, the About switches and the Android
 * AppFunctions all read and change the same state, so a switch turned off anywhere closes access everywhere.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object AssistantAccessModule {
    @Provides
    @Singleton
    fun filyPreferences(@ApplicationContext context: Context): FilyPreferences {
        val preferences = context.getSharedPreferences("fily", Context.MODE_PRIVATE)
        return object : FilyPreferences {
            override fun get(key: String): String? = preferences.getString(key, null)
            override fun put(key: String, value: String) {
                preferences.edit().putString(key, value).apply()
            }
            override fun putDurably(key: String, value: String) {
                preferences.edit().putString(key, value).commit()
            }
        }
    }

    @Provides
    @Singleton
    fun assistantAccess(preferences: FilyPreferences): AssistantAccess = AssistantAccess(preferences)

    /** Fily's controls for the open PDF. Shared by the process; the overlay attaches its target while open. */
    @Provides
    @Singleton
    fun readerControl(): ReaderControl = ReaderControl()
}
