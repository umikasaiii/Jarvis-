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
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class PersonalIntelligenceSnapshotCacheTest {

    private val now = Instant.parse("2026-01-01T10:00:00Z")

    /** Counts real builds by counting calls into the temporal provider — the builder itself has no counter of its own. */
    private fun countingBuilder(counter: IntArray): PersonalIntelligenceSnapshotBuilder = PersonalIntelligenceSnapshotBuilder(
        temporal = TemporalContextProvider { counter[0]++; TemporalContext(LocalDate.of(2026, 1, 1), LocalTime.of(10, 0), DayOfWeek.THURSDAY, null, null, now) },
        location = LocationContextProvider { LocationContext(PlaceLabel.HOME, "Casa", null, MovementState.STATIONARY, now) },
        agenda = AgendaContextProvider { AgendaContext(capturedAt = now) },
        driving = DrivingContextProvider { DrivingContext(capturedAt = now) },
        device = DeviceContextProvider { DeviceContext(capturedAt = now) },
        memory = MemoryContextProvider { MemoryContext(capturedAt = now) },
        recentEvents = RecentEventsProvider { RecentEventsContext(capturedAt = now) },
        task = TaskContextProvider { TaskContext(capturedAt = now) },
        capability = CapabilityContextProvider { CapabilityContext(capturedAt = now) },
    )

    @Test
    fun `two quick calls within the TTL reuse the same snapshot, one real build`() = runTest {
        val counter = intArrayOf(0)
        val cache = PersonalIntelligenceSnapshotCache(countingBuilder(counter))
        val first = cache.get()
        val second = cache.get()
        assertSame(first, second)
        assertEquals(1, counter[0])
    }

    @Test
    fun `forceRebuild always triggers a fresh build`() = runTest {
        val counter = intArrayOf(0)
        val cache = PersonalIntelligenceSnapshotCache(countingBuilder(counter))
        cache.get()
        cache.get(forceRebuild = true)
        assertEquals(2, counter[0])
    }

    @Test
    fun `invalidate forces the next get to rebuild`() = runTest {
        val counter = intArrayOf(0)
        val cache = PersonalIntelligenceSnapshotCache(countingBuilder(counter))
        cache.get()
        cache.invalidate()
        cache.get()
        assertEquals(2, counter[0])
    }
}
