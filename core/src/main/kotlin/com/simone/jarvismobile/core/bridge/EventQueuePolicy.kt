package com.simone.jarvismobile.core.bridge

/** One queued event plus when it was enqueued — the file-based queue's on-disk shape. */
data class QueuedEvent(val event: JarvisEvent, val enqueuedAtMs: Long)

/**
 * Pure eviction rules for Event Bridge's persisted queue (§ richiesta esplicita:
 * "eliminare automaticamente eventi vecchi/non più utili... non conservare
 * indefinitamente eventi sensibili"). No I/O here — the actual file (app
 * module) just calls [prune] before writing back, mirroring how
 * `CloudSyncManager`'s queue file is written but adding the staleness rule
 * that queue never had.
 */
object EventQueuePolicy {

    /**
     * How long an event may sit unsent before it is considered stale and
     * dropped, by priority — a [EventPriority.CRITICAL] event is worth
     * keeping far longer than a [EventPriority.LOW] one that has lost all
     * relevance by the time Core comes back online.
     */
    fun maxAgeMsFor(priority: EventPriority): Long = when (priority) {
        EventPriority.LOW -> 15 * 60_000L // 15 minutes
        EventPriority.NORMAL -> 60 * 60_000L // 1 hour
        EventPriority.HIGH -> 6 * 60 * 60_000L // 6 hours
        EventPriority.CRITICAL -> 24 * 60 * 60_000L // 24 hours
    }

    /**
     * Drops expired events first, then — only if [maxQueueSize] is still
     * exceeded — drops the oldest surviving events in ascending priority
     * order (LOW before NORMAL before HIGH before CRITICAL), oldest first
     * within each priority. Never invents/reorders events, never drops a
     * [EventPriority.CRITICAL] event unless every lower-priority one is
     * already gone and the queue is still over capacity.
     */
    fun prune(events: List<QueuedEvent>, nowMs: Long, maxQueueSize: Int): List<QueuedEvent> {
        val notExpired = events.filter { nowMs - it.enqueuedAtMs <= maxAgeMsFor(it.event.priority) }
        if (notExpired.size <= maxQueueSize) return notExpired

        val byPriority = notExpired.sortedWith(
            compareBy<QueuedEvent> { it.event.priority.ordinal }.thenBy { it.enqueuedAtMs },
        )
        val toDropCount = notExpired.size - maxQueueSize
        val dropped = byPriority.take(toDropCount).toSet()
        return notExpired.filterNot { it in dropped }
    }
}
