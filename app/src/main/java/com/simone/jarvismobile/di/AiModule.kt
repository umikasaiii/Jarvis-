package com.simone.jarvismobile.di

import com.simone.jarvismobile.ai.AiEngine
import com.simone.jarvismobile.ai.LocalAiEngine
import com.simone.jarvismobile.ai.LocalEngine
import com.simone.jarvismobile.ai.RemoteAiEngine
import com.simone.jarvismobile.ai.RemoteEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the two [AiEngine] implementations behind their qualifiers — see `AiEngineQualifiers.kt` for why [com.simone.jarvismobile.ai.AiRouter] depends on the interface rather than the concrete classes. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {
    @Binds
    @Singleton
    @LocalEngine
    abstract fun bindLocalEngine(impl: LocalAiEngine): AiEngine

    @Binds
    @Singleton
    @RemoteEngine
    abstract fun bindRemoteEngine(impl: RemoteAiEngine): AiEngine
}
