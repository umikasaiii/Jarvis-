package com.simone.jarvismobile.snapshot

import com.simone.jarvismobile.core.snapshot.AgendaContext
import com.simone.jarvismobile.core.snapshot.CapabilityContext
import com.simone.jarvismobile.core.snapshot.DeviceContext
import com.simone.jarvismobile.core.snapshot.DrivingContext
import com.simone.jarvismobile.core.snapshot.LocationContext
import com.simone.jarvismobile.core.snapshot.MemoryContext
import com.simone.jarvismobile.core.snapshot.MovementState
import com.simone.jarvismobile.core.snapshot.PlaceLabel
import com.simone.jarvismobile.core.snapshot.RecentEventsContext
import com.simone.jarvismobile.core.snapshot.TaskContext
import com.simone.jarvismobile.core.snapshot.TemporalContext
import com.simone.jarvismobile.snapshot.providers.AgendaContextProvider
import com.simone.jarvismobile.snapshot.providers.CapabilityContextProvider
import com.simone.jarvismobile.snapshot.providers.DeviceContextProvider
import com.simone.jarvismobile.snapshot.providers.DrivingContextProvider
import com.simone.jarvismobile.snapshot.providers.LocationContextProvider
import com.simone.jarvismobile.snapshot.providers.MemoryContextProvider
import com.simone.jarvismobile.snapshot.providers.RecentEventsProvider
import com.simone.jarvismobile.snapshot.providers.TaskContextProvider
import com.simone.jarvismobile.snapshot.providers.TemporalContextProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Covers the explicitly required scenarios: a full snapshot, a snapshot
 * with missing sources, and a single provider throwing without blocking
 * the rest — all via fakes, no Android `Context` needed (§ nine provider
 * interfaces exist specifically for this).
 */
class PersonalIntelligenceSnapshotBuilderTest {

    private val now = Instant.parse("2026-01-01T10:00:00Z")

    private fun temporal(value: TemporalContext? = TemporalContext(LocalDate.of(2026, 1, 1), LocalTime.of(10, 0), DayOfWeek.THURSDAY, null, null, now)) =
        TemporalContextProvider { value }

    private fun builder(
        temporal: TemporalContextProvider = temporal(),
        location: LocationContextProvider = LocationContextProvider { LocationContext(PlaceLabel.HOME, "Casa", null, MovementState.STATIONARY, now) },
        agenda: AgendaContextProvider = AgendaContextProvider { AgendaContext(capturedAt = now) },
        driving: DrivingContextProvider = DrivingContextProvider { DrivingContext(capturedAt = now) },
        device: DeviceContextProvider = DeviceContextProvider { DeviceContext(capturedAt = now) },
        memory: MemoryContextProvider = MemoryContextProvider { MemoryContext(capturedAt = now) },
        recentEvents: RecentEventsProvider = RecentEventsProvider { RecentEventsContext(capturedAt = now) },
        task: TaskContextProvider = TaskContextProvider { TaskContext(capturedAt = now) },
        capability: CapabilityContextProvider = CapabilityContextProvider { CapabilityContext(capturedAt = now) },
    ) = PersonalIntelligenceSnapshotBuilder(temporal, location, agenda, driving, device, memory, recentEvents, task, capability)

    @Test
    fun `full snapshot has every section and every source marked available`() = runTest {
        val snapshot = builder().build()
        assertNotNull(snapshot.temporal)
        assertNotNull(snapshot.location)
        assertNotNull(snapshot.agenda)
        assertNotNull(snapshot.driving)
        assertNotNull(snapshot.device)
        assertNotNull(snapshot.memory)
        assertNotNull(snapshot.recentEvents)
        assertNotNull(snapshot.task)
        assertNotNull(snapshot.capability)
        assertEquals(9, snapshot.sourceSummary.available.size)
        assertTrue(snapshot.sourceSummary.missing.isEmpty())
        assertEquals(1, snapshot.schemaVersion)
        assertTrue(snapshot.snapshotId.isNotBlank())
    }

    @Test
    fun `a missing source is simply absent, never a crash`() = runTest {
        val snapshot = builder(agenda = AgendaContextProvider { null }).build()
        assertNull(snapshot.agenda)
        assertTrue("agenda" in snapshot.sourceSummary.missing)
        assertTrue("agenda" !in snapshot.sourceSummary.available)
        assertNotNull(snapshot.temporal) // everything else still built
    }

    @Test
    fun `a throwing provider never blocks the rest of the snapshot`() = runTest {
        val snapshot = builder(driving = DrivingContextProvider { throw IllegalStateException("boom") }).build()
        assertNull(snapshot.driving)
        assertTrue("driving" in snapshot.sourceSummary.missing)
        assertNotNull(snapshot.temporal)
        assertNotNull(snapshot.agenda)
        assertNotNull(snapshot.capability)
    }

    @Test
    fun `every provider throwing still produces a snapshot, just an empty one`() = runTest {
        val snapshot = builder(
            temporal = TemporalContextProvider { throw RuntimeException() },
            location = LocationContextProvider { throw RuntimeException() },
            agenda = AgendaContextProvider { throw RuntimeException() },
            driving = DrivingContextProvider { throw RuntimeException() },
            device = DeviceContextProvider { throw RuntimeException() },
            memory = MemoryContextProvider { throw RuntimeException() },
            recentEvents = RecentEventsProvider { throw RuntimeException() },
            task = TaskContextProvider { throw RuntimeException() },
            capability = CapabilityContextProvider { throw RuntimeException() },
        ).build()
        assertEquals(0, snapshot.sourceSummary.available.size)
        assertEquals(9, snapshot.sourceSummary.missing.size)
        assertTrue(snapshot.snapshotId.isNotBlank()) // the snapshot itself is still valid
    }
}
