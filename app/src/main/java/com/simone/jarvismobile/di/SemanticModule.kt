package com.simone.jarvismobile.di

import com.simone.jarvismobile.core.semantic.SemanticInterpreter
import com.simone.jarvismobile.engine.semantic.LocalLlmSemanticInterpreter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** § FASE 2A.9 — binds the real, on-device [SemanticInterpreter] implementation so callers depend only on the interface. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SemanticModule {
    @Binds
    @Singleton
    abstract fun bindSemanticInterpreter(impl: LocalLlmSemanticInterpreter): SemanticInterpreter
}
