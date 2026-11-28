package com.xfiles.android

import com.xfiles.crash.CrashReports
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface AppModule {
    @Binds
    fun crashReports(reports: AndroidCrashReports): CrashReports
}
