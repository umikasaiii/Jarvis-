package com.simone.jarvismobile.snapshot

import android.util.Log
import com.simone.jarvismobile.core.snapshot.PersonalIntelligenceSnapshot
import com.simone.jarvismobile.core.snapshot.SourceSummary
import com.simone.jarvismobile.snapshot.providers.AgendaContextProvider
import com.simone.jarvismobile.snapshot.providers.CapabilityContextProvider
import com.simone.jarvismobile.snapshot.providers.DeviceContextProvider
import com.simone.jarvismobile.snapshot.providers.DrivingContextProvider
import com.simone.jarvismobile.snapshot.providers.LocationContextProvider
import com.simone.jarvismobile.snapshot.providers.MemoryContextProvider
import com.simone.jarvismobile.snapshot.providers.RecentEventsProvider
import com.simone.jarvismobile.snapshot.providers.TaskContextProvider
import com.simone.jarvismobile.snapshot.providers.TemporalContextProvider
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds one [PersonalIntelligenceSnapshot] from the nine providers — each
 * called independently and wrapped so a single failing source (e.g. Agenda
 * throwing) never prevents the rest from contributing (§ richiesta
 * esplicita: "Ogni provider deve poter fallire indipendentemente. Un
 * errore Agenda NON deve impedire la costruzione dello snapshot"). Contains
 * no business logic of its own — every actual decision (place labels,
 * agenda filtering, freshness) lives in the provider or in `:core`.
 */
@Singleton
class PersonalIntelligenceSnapshotBuilder @Inject constructor(
    private val temporal: TemporalContextProvider,
    private val location: LocationContextProvider,
    private val agenda: AgendaContextProvider,
    private val driving: DrivingContextProvider,
    private val device: DeviceContextProvider,
    private val memory: MemoryContextProvider,
    private val recentEvents: RecentEventsProvider,
    private val task: TaskContextProvider,
    private val capability: CapabilityContextProvider,
) {
    suspend fun build(): PersonalIntelligenceSnapshot {
        val available = mutableSetOf<String>()
        val missing = mutableSetOf<String>()

        fun <T> section(name: String, block: suspend () -> T?): T? {
            val result = runCatching { block() }.getOrElse { e ->
                Log.w(TAG, "snapshot_provider_failed source=$name ${e.javaClass.simpleName}")
                null
            }
            if (result != null) available += name else missing += name
            return result
        }

        return PersonalIntelligenceSnapshot(
            snapshotId = UUID.randomUUID().toString(),
            createdAt = Instant.now(),
            temporal = section("temporal") { temporal.provide() },
            location = section("location") { location.provide() },
            agenda = section("agenda") { agenda.provide() },
            driving = section("driving") { driving.provide() },
            device = section("device") { device.provide() },
            memory = section("memory") { memory.provide() },
            recentEvents = section("recentEvents") { recentEvents.provide() },
            task = section("task") { task.provide() },
            capability = section("capability") { capability.provide() },
            sourceSummary = SourceSummary(available = available, missing = missing),
        )
    }

    private companion object {
        const val TAG = "PersonalIntelSnapshot"
    }
}
