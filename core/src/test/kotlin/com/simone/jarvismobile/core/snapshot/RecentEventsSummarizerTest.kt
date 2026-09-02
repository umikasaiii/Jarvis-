package com.simone.jarvismobile.core.snapshot

import com.simone.jarvismobile.core.bridge.EventPriority
import com.simone.jarvismobile.core.bridge.JarvisEvent
import com.simone.jarvismobile.core.bridge.JarvisEventType
import com.simone.jarvismobile.core.bridge.QueuedEvent
import com.simone.jarvismobile.core.tools.SensitivityLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentEventsSummarizerTest {

    private fun event(
        id: String,
        type: JarvisEventType,
        timestampMs: Long,
        priority: EventPriority = EventPriority.NORMAL,
    ) = QueuedEvent(
        JarvisEvent(id = id, type = type, timestampMs = timestampMs, source = "test", priority = priority, privacyLevel = SensitivityLevel.PUBLIC),
        enqueuedAtMs = timestampMs,
    )

    @Test
    fun `a burst of the same type collapses to one, the most recent`() {
        val events = listOf(
            event("1", JarvisEventType.NETWORK_CHANGED, 1_000L),
            event("2", JarvisEventType.NETWORK_CHANGED, 2_000L),
            event("3", JarvisEventType.NETWORK_CHANGED, 3_000L),
        )
        val result = RecentEventsSummarizer.summarize(events, maxEvents = 5)
        assertEquals(1, result.size)
        assertEquals(3_000L, result.first().timestampMs)
    }

    @Test
    fun `different types each survive deduplication`() {
        val events = listOf(
            event("1", JarvisEventType.NETWORK_CHANGED, 1_000L),
            event("2", JarvisEventType.USER_UNLOCKED, 2_000L),
            event("3", JarvisEventType.HEADPHONES_CONNECTED, 3_000L),
        )
        val result = RecentEventsSummarizer.summarize(events, maxEvents = 5)
        assertEquals(3, result.size)
    }

    @Test
    fun `caps at maxEvents, preferring higher priority`() {
        val events = listOf(
            event("low", JarvisEventType.NETWORK_CHANGED, 1_000L, EventPriority.LOW),
            event("crit", JarvisEventType.USER_UNLOCKED, 500L, EventPriority.CRITICAL),
            event("normal", JarvisEventType.HEADPHONES_CONNECTED, 2_000L, EventPriority.NORMAL),
        )
        val result = RecentEventsSummarizer.summarize(events, maxEvents = 2)
        assertEquals(2, result.size)
        assertTrue(result.any { it.type == JarvisEventType.USER_UNLOCKED.name })
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals(emptyList(), RecentEventsSummarizer.summarize(emptyList(), maxEvents = 5))
    }

    @Test
    fun `maxEvents of zero produces nothing`() {
        val events = listOf(event("1", JarvisEventType.APP_STARTED, 1_000L))
        assertEquals(emptyList(), RecentEventsSummarizer.summarize(events, maxEvents = 0))
    }
}
