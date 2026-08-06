package com.simone.jarvismobile.di

import com.simone.jarvismobile.llm.LlmEngine
import com.simone.jarvismobile.llm.LlmRouter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The two brains. `LitertLmEngine` is unscoped, so [LlmRouter] receives two
 * distinct instances: a small always-on model and an optional larger one.
 *
 * Anything that just needs "the model" (model management, intent classification)
 * gets the fast engine; only conversation routing goes through the router.
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    fun provideLlmEngine(router: LlmRouter): LlmEngine = router.fast
}
