package com.simone.jarvismobile.core.snapshot

import com.simone.jarvismobile.core.bridge.QueuedEvent

/**
 * Turns the same [QueuedEvent] queue already built for Core delivery
 * (`EventQueueStore`, § Event Bridge) into a small, presentation-ready
 * [RecentEventsContext] — a distinct concern from
 * [com.simone.jarvismobile.core.bridge.EventQueuePolicy]'s own TTL/capacity
 * pruning (that one protects delivery reliability; this one produces a
 * short, deduplicated ambient summary), so it does not touch or duplicate
 * that policy's rules, only reads the same events.
 *
 * "Deduplicazione" (§ richiesta esplicita) means: at most one entry per
 * event *type*, keeping the most recent occurrence — a burst of five
 * `NETWORK_CHANGED` events becomes one summary line, not five.
 */
object RecentEventsSummarizer {
    fun summarize(events: List<QueuedEvent>, maxEvents: Int): List<RecentEventSummary> {
        if (events.isEmpty() || maxEvents <= 0) return emptyList()
        val mostRecentPerType = events
            .groupBy { it.event.type }
            .mapValues { (_, group) -> group.maxByOrNull { it.event.timestampMs } }
            .values
            .filterNotNull()
        return mostRecentPerType
            .sortedWith(compareByDescending<QueuedEvent> { it.event.priority.ordinal }.thenByDescending { it.event.timestampMs })
            .take(maxEvents)
            .map { RecentEventSummary(type = it.event.type.name, timestampMs = it.event.timestampMs, priority = it.event.priority.name) }
    }
}
