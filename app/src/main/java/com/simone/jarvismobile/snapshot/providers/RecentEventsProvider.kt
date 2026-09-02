package com.simone.jarvismobile.snapshot.providers

import com.simone.jarvismobile.core.snapshot.RecentEventsContext
import com.simone.jarvismobile.core.snapshot.RecentEventsSummarizer
import com.simone.jarvismobile.corebridge.EventQueue
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reuses the exact same event queue already built for Event Bridge
 * (`EventQueueStore`, § Core phase) — no second event log. Deduplication/
 * capping is [RecentEventsSummarizer]'s job (pure, `:core`), distinct from
 * [com.simone.jarvismobile.core.bridge.EventQueuePolicy]'s own delivery-
 * reliability pruning.
 */
private const val PROVIDER_MAX_EVENTS = 10

fun interface RecentEventsProvider {
    suspend fun provide(): RecentEventsContext?
}

@Singleton
class DefaultRecentEventsProvider @Inject constructor(
    private val queue: EventQueue,
) : RecentEventsProvider {

    override suspend fun provide(): RecentEventsContext? {
        val pending = runCatching { queue.peekAll() }.getOrNull() ?: return null
        if (pending.isEmpty()) return null
        return RecentEventsContext(events = RecentEventsSummarizer.summarize(pending, PROVIDER_MAX_EVENTS), capturedAt = Instant.now())
    }
}
