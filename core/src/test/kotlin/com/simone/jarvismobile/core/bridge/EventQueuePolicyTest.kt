package com.simone.jarvismobile.core.bridge

import com.simone.jarvismobile.core.tools.SensitivityLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventQueuePolicyTest {

    private fun event(
        id: String,
        priority: EventPriority = EventPriority.NORMAL,
        privacy: SensitivityLevel = SensitivityLevel.PUBLIC,
    ) = JarvisEvent(
        id = id,
        type = JarvisEventType.APP_STARTED,
        timestampMs = 0L,
        source = "test",
        priority = priority,
        privacyLevel = privacy,
    )

    @Test
    fun `expired low-priority event is dropped`() {
        val now = 1_000_000L
        val stale = QueuedEvent(event("a", EventPriority.LOW), enqueuedAtMs = now - EventQueuePolicy.maxAgeMsFor(EventPriority.LOW) - 1)
        val result = EventQueuePolicy.prune(listOf(stale), now, maxQueueSize = 100)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fresh event within its TTL survives`() {
        val now = 1_000_000L
        val fresh = QueuedEvent(event("a", EventPriority.LOW), enqueuedAtMs = now - 1_000L)
        val result = EventQueuePolicy.prune(listOf(fresh), now, maxQueueSize = 100)
        assertEquals(listOf(fresh), result)
    }

    @Test
    fun `critical event survives far longer than low priority`() {
        val now = 1_000_000L
        val ageThatKillsLow = EventQueuePolicy.maxAgeMsFor(EventPriority.LOW) + 1
        val critical = QueuedEvent(event("a", EventPriority.CRITICAL), enqueuedAtMs = now - ageThatKillsLow)
        val result = EventQueuePolicy.prune(listOf(critical), now, maxQueueSize = 100)
        assertEquals(listOf(critical), result)
    }

    @Test
    fun `over capacity drops oldest low-priority events first`() {
        val now = 0L
        val low1 = QueuedEvent(event("low1", EventPriority.LOW), enqueuedAtMs = now)
        val low2 = QueuedEvent(event("low2", EventPriority.LOW), enqueuedAtMs = now + 1)
        val critical = QueuedEvent(event("crit", EventPriority.CRITICAL), enqueuedAtMs = now)
        val result = EventQueuePolicy.prune(listOf(low1, low2, critical), now, maxQueueSize = 2)
        assertEquals(setOf("low2", "crit"), result.map { it.event.id }.toSet())
    }

    @Test
    fun `never invents or reorders surviving events beyond dropping the oldest low ones`() {
        val now = 0L
        val events = (1..5).map { QueuedEvent(event("e$it", EventPriority.NORMAL), enqueuedAtMs = now + it) }
        val result = EventQueuePolicy.prune(events, now, maxQueueSize = 3)
        assertEquals(listOf("e3", "e4", "e5"), result.map { it.event.id })
    }

    @Test
    fun `empty queue stays empty`() {
        assertTrue(EventQueuePolicy.prune(emptyList(), 0L, maxQueueSize = 10).isEmpty())
    }
}
