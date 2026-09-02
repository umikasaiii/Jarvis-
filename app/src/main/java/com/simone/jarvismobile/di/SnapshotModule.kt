package com.simone.jarvismobile.di

import com.simone.jarvismobile.snapshot.providers.AgendaContextProvider
import com.simone.jarvismobile.snapshot.providers.CapabilityContextProvider
import com.simone.jarvismobile.snapshot.providers.DefaultAgendaContextProvider
import com.simone.jarvismobile.snapshot.providers.DefaultCapabilityContextProvider
import com.simone.jarvismobile.snapshot.providers.DefaultDeviceContextProvider
import com.simone.jarvismobile.snapshot.providers.DefaultDrivingContextProvider
import com.simone.jarvismobile.snapshot.providers.DefaultLocationContextProvider
import com.simone.jarvismobile.snapshot.providers.DefaultMemoryContextProvider
import com.simone.jarvismobile.snapshot.providers.DefaultRecentEventsProvider
import com.simone.jarvismobile.snapshot.providers.DefaultTaskContextProvider
import com.simone.jarvismobile.snapshot.providers.DefaultTemporalContextProvider
import com.simone.jarvismobile.snapshot.providers.DeviceContextProvider
import com.simone.jarvismobile.snapshot.providers.DrivingContextProvider
import com.simone.jarvismobile.snapshot.providers.LocationContextProvider
import com.simone.jarvismobile.snapshot.providers.MemoryContextProvider
import com.simone.jarvismobile.snapshot.providers.RecentEventsProvider
import com.simone.jarvismobile.snapshot.providers.TaskContextProvider
import com.simone.jarvismobile.snapshot.providers.TemporalContextProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the nine `PersonalIntelligenceSnapshot` context providers to their real implementations — each interface exists so `PersonalIntelligenceSnapshotBuilder` can be tested with fakes (§ `AiRoutingContextProvider`/`EventBridgeGate` same pattern). */
@Module
@InstallIn(SingletonComponent::class)
abstract class SnapshotModule {
    @Binds @Singleton abstract fun bindTemporal(impl: DefaultTemporalContextProvider): TemporalContextProvider
    @Binds @Singleton abstract fun bindLocation(impl: DefaultLocationContextProvider): LocationContextProvider
    @Binds @Singleton abstract fun bindAgenda(impl: DefaultAgendaContextProvider): AgendaContextProvider
    @Binds @Singleton abstract fun bindDriving(impl: DefaultDrivingContextProvider): DrivingContextProvider
    @Binds @Singleton abstract fun bindDevice(impl: DefaultDeviceContextProvider): DeviceContextProvider
    @Binds @Singleton abstract fun bindMemory(impl: DefaultMemoryContextProvider): MemoryContextProvider
    @Binds @Singleton abstract fun bindRecentEvents(impl: DefaultRecentEventsProvider): RecentEventsProvider
    @Binds @Singleton abstract fun bindTask(impl: DefaultTaskContextProvider): TaskContextProvider
    @Binds @Singleton abstract fun bindCapability(impl: DefaultCapabilityContextProvider): CapabilityContextProvider
}
