package com.simone.jarvismobile.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherReceiptRetentionPolicyTest {

    private val oneDayMs = 24L * 60L * 60L * 1000L

    @Test fun cutoffMs_defaultIsNinetyDaysBack() {
        val now = 1_000_000_000_000L
        val expected = now - 90L * oneDayMs
        assertEquals(expected, WeatherReceiptRetentionPolicy.cutoffMs(now))
    }

    @Test fun cutoffMs_customRetentionRespected() {
        val now = 1_000_000_000_000L
        assertEquals(now - 30L * oneDayMs, WeatherReceiptRetentionPolicy.cutoffMs(now, retentionDays = 30L))
    }

    @Test fun cutoffMs_negativeRetentionCoercedToZero_neverInTheFuture() {
        val now = 1_000_000_000_000L
        assertEquals(now, WeatherReceiptRetentionPolicy.cutoffMs(now, retentionDays = -5L))
    }

    @Test fun excessRowCount_zeroWhenWithinBounds() {
        assertEquals(0, WeatherReceiptRetentionPolicy.excessRowCount(currentRowCount = 100, maxRows = 4096))
        assertEquals(0, WeatherReceiptRetentionPolicy.excessRowCount(currentRowCount = 4096, maxRows = 4096))
    }

    @Test fun excessRowCount_exactExcessWhenOverBounds() {
        assertEquals(1, WeatherReceiptRetentionPolicy.excessRowCount(currentRowCount = 4097, maxRows = 4096))
        assertEquals(500, WeatherReceiptRetentionPolicy.excessRowCount(currentRowCount = 4596, maxRows = 4096))
    }

    @Test fun excessRowCount_neverNegative() {
        assertEquals(0, WeatherReceiptRetentionPolicy.excessRowCount(currentRowCount = 0, maxRows = 4096))
    }

    @Test fun defaults_matchWorkPackageDSpec() {
        assertEquals(90L, WeatherReceiptRetentionPolicy.DEFAULT_RETENTION_DAYS)
        assertEquals(4096, WeatherReceiptRetentionPolicy.DEFAULT_MAX_ROWS)
    }

    /**
     * §3's core invariant: a row just written "now" (evaluatedAtUtcMs == now)
     * is never age-eligible for deletion under any sane retention window,
     * and — since deletion by count always targets the OLDEST rows first,
     * ordered ascending by rowSeq (an insert-order-monotonic key) — the
     * newest row is never among the "oldest N" selected by excessRowCount
     * as long as the table has at most that many rows beyond it. This test
     * pins the age-based half directly; the count-based half is a property
     * of the SQL `ORDER BY rowSeq ASC LIMIT :excess` query itself (app-side,
     * not expressible here), documented instead of re-implemented.
     */
    @Test fun newestRowNeverEligibleForDeletion() {
        val now = 1_000_000_000_000L
        val cutoff = WeatherReceiptRetentionPolicy.cutoffMs(now)
        assertTrue(now >= cutoff, "a row evaluated at 'now' must never be older than the cutoff")
    }
}
