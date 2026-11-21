package com.xfiles.feature.assistant

import com.xfiles.documentengine.FilyInference
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

/** FilyInference lives in the KMP engine module, which has no Hilt, so it is provided here. */
@Module
@InstallIn(ViewModelComponent::class)
internal object AssistantModule {
    /** One loaded model per assistant ViewModel; the ViewModel closes it in onCleared. */
    @Provides
    @ViewModelScoped
    fun filyInference(): FilyInference = FilyInference()
}
