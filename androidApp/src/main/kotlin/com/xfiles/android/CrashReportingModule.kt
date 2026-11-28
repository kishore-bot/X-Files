package com.xfiles.android

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object CrashReportingModule {
    /** [createCrashReporting] comes from `src/firebase` or `src/offline`, whichever the build selects. */
    @Provides
    @Singleton
    fun crashReportingSetup(@ApplicationContext context: Context): CrashReportingSetup = createCrashReporting(context)
}
