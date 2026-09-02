package com.simone.jarvismobile.core.snapshot

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotFreshnessPolicyTest {

    @Test
    fun `just captured is fresh`() {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        val result = SnapshotFreshnessPolicy.of(now, ttlMs = 60_000L, now = now)
        assertEquals(Freshness.FRESH, result.freshness)
        assertEquals(0L, result.ageMs)
    }

    @Test
    fun `past half the ttl is aging`() {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        val capturedAt = now.minusSeconds(35)
        val result = SnapshotFreshnessPolicy.of(capturedAt, ttlMs = 60_000L, now = now)
        assertEquals(Freshness.AGING, result.freshness)
    }

    @Test
    fun `past the full ttl is stale`() {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        val capturedAt = now.minusSeconds(120)
        val result = SnapshotFreshnessPolicy.of(capturedAt, ttlMs = 60_000L, now = now)
        assertEquals(Freshness.STALE, result.freshness)
        assertTrue(SnapshotFreshnessPolicy.isStale(capturedAt, 60_000L, now))
    }

    @Test
    fun `a stale old location is never treated as current`() {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        val capturedAt = now.minusSeconds(20 * 60) // 20 minutes, location TTL is 10 minutes
        assertTrue(SnapshotFreshnessPolicy.isStale(capturedAt, SnapshotFreshnessPolicy.LOCATION_TTL_MS, now))
    }

    @Test
    fun `fresh section within ttl is not stale`() {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        val capturedAt = now.minusSeconds(30)
        assertFalse(SnapshotFreshnessPolicy.isStale(capturedAt, SnapshotFreshnessPolicy.LOCATION_TTL_MS, now))
    }
}
