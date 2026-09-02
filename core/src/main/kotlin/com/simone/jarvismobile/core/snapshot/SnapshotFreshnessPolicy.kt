package com.simone.jarvismobile.core.snapshot

import java.time.Duration
import java.time.Instant

/** How stale a single section is, relative to its own TTL. */
enum class Freshness { FRESH, AGING, STALE }

/** Per-section TTL + staleness verdict, alongside its raw age. */
data class SectionFreshness(val ageMs: Long, val freshness: Freshness)

/**
 * Per-category TTLs for `PersonalIntelligenceSnapshot` sections (§ richiesta
 * esplicita §18: "Definire TTL appropriati per le diverse categorie... non
 * utilizzare una posizione vecchia come se fosse attuale"). Each TTL mirrors
 * the staleness discipline the underlying source already uses where one
 * exists — location follows `PlaceFusion`'s own `signalTtl` (~10min),
 * device mirrors how often `ContextEngine`'s battery/network reads are
 * meaningful, driving is short-lived by nature.
 */
object SnapshotFreshnessPolicy {
    const val TEMPORAL_TTL_MS = 60_000L
    const val LOCATION_TTL_MS = 10 * 60_000L
    const val AGENDA_TTL_MS = 5 * 60_000L
    const val DRIVING_TTL_MS = 30_000L
    const val DEVICE_TTL_MS = 2 * 60_000L
    const val MEMORY_TTL_MS = 10 * 60_000L
    const val RECENT_EVENTS_TTL_MS = 15 * 60_000L
    const val TASK_TTL_MS = 5 * 60_000L
    const val CAPABILITY_TTL_MS = 30_000L

    /** [Freshness.AGING] once past half the TTL, [Freshness.STALE] once past it entirely — never a hard cliff at exactly the TTL. */
    fun of(capturedAt: Instant, ttlMs: Long, now: Instant): SectionFreshness {
        val ageMs = Duration.between(capturedAt, now).toMillis().coerceAtLeast(0L)
        val freshness = when {
            ageMs >= ttlMs -> Freshness.STALE
            ageMs >= ttlMs / 2 -> Freshness.AGING
            else -> Freshness.FRESH
        }
        return SectionFreshness(ageMs, freshness)
    }

    /** True when a section this old should NOT be treated as current (§ "non utilizzare una posizione vecchia come se fosse attuale"). */
    fun isStale(capturedAt: Instant, ttlMs: Long, now: Instant): Boolean = of(capturedAt, ttlMs, now).freshness == Freshness.STALE
}
