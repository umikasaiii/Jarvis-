package com.simone.jarvismobile.di

import com.simone.jarvismobile.core.semantic.SemanticInterpreter
import com.simone.jarvismobile.engine.semantic.LocalLlmSemanticInterpreter
import com.simone.jarvismobile.engine.semantic.SemanticInterpreterRouter
import com.simone.jarvismobile.llm.EmbeddingGemmaEngine
import com.simone.jarvismobile.llm.SemanticEmbeddingEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * § FASE 2A.11 §14 — the legacy FASE 2A.9 generative interpreter, kept
 * injectable ONLY under this qualifier for [SemanticInterpreterRouter]'s
 * debug A/B path (§14: "Il vecchio Gemma 1B Semantic Interpreter può restare
 * selezionabile SOLO in debug... Non usarlo automaticamente come autorità
 * semantica primaria"). Nothing else in the app injects a
 * [SemanticInterpreter] with this qualifier.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LegacyGemmaInterpreter

/**
 * § FASE 2A.9 → § FASE 2A.11 — binds the real, on-device [SemanticInterpreter].
 * The primary (unqualified) binding is now [SemanticInterpreterRouter],
 * which defaults to the EmbeddingGemma classifier and only reaches the FASE
 * 2A.9 generative interpreter ([LocalLlmSemanticInterpreter], bound under
 * [LegacyGemmaInterpreter]) behind an explicit debug toggle — never
 * automatically, never as a fallback.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SemanticModule {

    @Binds
    @Singleton
    abstract fun bindSemanticInterpreter(router: SemanticInterpreterRouter): SemanticInterpreter

    @Binds
    @Singleton
    abstract fun bindSemanticEmbeddingEngine(impl: EmbeddingGemmaEngine): SemanticEmbeddingEngine

    companion object {
        @Provides
        @Singleton
        @LegacyGemmaInterpreter
        fun provideLegacyGemmaInterpreter(impl: LocalLlmSemanticInterpreter): SemanticInterpreter = impl
    }
}
