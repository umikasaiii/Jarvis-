package com.simone.jarvismobile.net

import com.simone.jarvismobile.ai.AiRouter
import com.simone.jarvismobile.corebridge.CoreClient
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.ModelProfileStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Production singletons the offline/profile instrumented tests reach through the real Hilt graph (same pattern as the other entry points). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OfflineEntryPoint {
    fun coreClient(): CoreClient
    fun settings(): SettingsRepository
    fun profiles(): ModelProfileStore
    fun aiRouter(): AiRouter
}
