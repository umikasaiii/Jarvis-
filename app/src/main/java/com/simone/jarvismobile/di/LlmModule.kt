package com.simone.jarvismobile.di

import com.simone.jarvismobile.llm.ClassifierEngineProvider
import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmRouter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The three brains. `LitertLmEngine` is unscoped, so [LlmRouter] receives three
 * distinct instances: a small always-on model, an optional larger one for hard
 * questions, and an optional third one dedicated purely to intent
 * classification (see `LlmRouter.classifierEngine`).
 *
 * Anything that just needs "the model" (model management) gets the fast
 * engine; conversation routing and intent classification go through the
 * router, which picks the right instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    fun provideLlmEngine(router: LlmRouter): LlmEngine = router.fast

    @Provides
    @Singleton
    fun provideClassifierEngineProvider(router: LlmRouter): ClassifierEngineProvider = router
}
