package com.xfiles.android.appfunctions

import com.xfiles.documentengine.DocumentPassages
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Fily's passage index lives for the process, so a document indexed once answers later questions from memory. */
@Module
@InstallIn(SingletonComponent::class)
object PassagesModule {
    @Provides
    @Singleton
    fun documentPassages(): DocumentPassages = DocumentPassages()
}