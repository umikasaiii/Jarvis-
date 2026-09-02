package com.simone.jarvismobile.di

import com.simone.jarvismobile.corebridge.CoreClient
import com.simone.jarvismobile.corebridge.DefaultEventBridgeGate
import com.simone.jarvismobile.corebridge.EventBridgeGate
import com.simone.jarvismobile.corebridge.EventBridgeScope
import com.simone.jarvismobile.corebridge.EventQueue
import com.simone.jarvismobile.corebridge.EventQueueStore
import com.simone.jarvismobile.corebridge.JarvisCoreClientImpl
import com.simone.jarvismobile.ai.AiRoutingContextProvider
import com.simone.jarvismobile.ai.DefaultAiRoutingContextProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Binds the JARVIS Core / AI Router / Event Bridge interfaces to their real
 * implementations — same `@Binds` pattern already used by `AudioModule`/
 * `WeatherModule`. The interfaces themselves ([EventQueue], [EventBridgeGate],
 * [AiRoutingContextProvider]) exist specifically so their consumers
 * ([com.simone.jarvismobile.corebridge.EventBridge],
 * [com.simone.jarvismobile.ai.AiRouter]) can be unit-tested with fakes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {
    @Binds
    @Singleton
    abstract fun bindCoreClient(impl: JarvisCoreClientImpl): CoreClient

    @Binds
    @Singleton
    abstract fun bindEventQueue(impl: EventQueueStore): EventQueue

    @Binds
    @Singleton
    abstract fun bindEventBridgeGate(impl: DefaultEventBridgeGate): EventBridgeGate

    @Binds
    @Singleton
    abstract fun bindAiRoutingContextProvider(impl: DefaultAiRoutingContextProvider): AiRoutingContextProvider

    companion object {
        @Provides
        @Singleton
        @EventBridgeScope
        fun provideEventBridgeScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
