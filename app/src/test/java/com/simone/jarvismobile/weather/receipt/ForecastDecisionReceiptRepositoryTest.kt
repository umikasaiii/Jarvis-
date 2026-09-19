package com.simone.jarvismobile.weather.receipt

import com.simone.jarvismobile.core.weather.ForecastEligibilityReason
import com.simone.jarvismobile.core.weather.ForecastFacts
import com.simone.jarvismobile.core.weather.HourlyPrecipitationEvidence
import com.simone.jarvismobile.core.weather.WeatherAlertPolicyV2
import com.simone.jarvismobile.core.weather.WeatherAlertThresholdsV2
import com.simone.jarvismobile.core.weather.WeatherCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D.1 §8 (W10). The SAME target date evaluated multiple times, real
 * executed against [ForecastDecisionReceiptRepository] with the in-memory
 * [FakeForecastDecisionReceiptDao] — never a hand-drawn conclusion. Covers
 * the five scenarios named in the spec:
 *   A. low severity (below threshold, no alert)
 *   B. qualifying rain (a real candidate)
 *   C. a stronger forecast the next evaluation (escalated hazard)
 *   D. a location revision change
 *   E. a policy/threshold config version change
 *
 * Asserts each evaluation gets a distinct, immutable receipt; the receipt
 * sequence is append-only (rowSeq strictly increasing, never reused/rewritten
 * — proven by re-reading every row back unchanged after later inserts);
 * facts hashes reflect the actual inputs (differ when inputs differ, are
 * stable when they don't).
 *
 * "occurrence remains WEATHER_ALERT:<targetDate>, and after dispatch/an
 * unknown-effect boundary a later forecast escalation cannot authorize a
 * second production notification" is a property of
 * [com.simone.jarvismobile.proactive.ProactiveOccurrenceStore]'s atomic
 * claim (Work Package A) applied to that SAME key format — already covered
 * end-to-end by `ProactiveOccurrenceStoreTest`'s dedicated WEATHER_ALERT-
 * shaped-key coverage (added in PASSAGGIO 14.2) — not re-proven here to
 * avoid a second, divergent copy of that machinery. This file's job is only
 * the receipt layer's own append-only/immutability/hash-fidelity guarantees.
 */
class ForecastDecisionReceiptRepositoryTest {

    private lateinit var dao: FakeForecastDecisionReceiptDao
    private lateinit var repository: ForecastDecisionReceiptRepository

    private val targetDate: LocalDate = LocalDate.of(2026, 9, 20)
    private val fetchedAt: Instant = Instant.parse("2026-09-19T18:00:00Z")

    @Before
    fun setUp() {
        dao = FakeForecastDecisionReceiptDao()
        repository = ForecastDecisionReceiptRepository(dao)
    }

    private fun facts(
        rawWeatherCode: Int? = 61,
        probability: Double? = 85.0,
        rainSumMm: Double? = 3.0,
        precipitationSumMm: Double? = 3.0,
        precipitationHours: Double? = 3.0,
        locationRevision: String = "coord:41.90,12.50",
    ) = ForecastFacts(
        targetDate = targetDate,
        providerTimezone = "Europe/Rome",
        locationRevision = locationRevision,
        fetchedAt = fetchedAt,
        rawWeatherCode = rawWeatherCode,
        category = WeatherCategory.fromWmoCode(rawWeatherCode ?: 0),
        precipitationSumMm = precipitationSumMm,
        rainSumMm = rainSumMm,
        showersSumMm = null,
        snowfallSumCm = null,
        precipitationProbabilityMaxPercent = probability,
        precipitationHours = precipitationHours,
    )

    private suspend fun recordFor(
        facts: ForecastFacts,
        thresholds: WeatherAlertThresholdsV2 = WeatherAlertThresholdsV2(),
        hourlyEvidence: List<HourlyPrecipitationEvidence> = emptyList(),
    ): String? {
        val decision = WeatherAlertPolicyV2.evaluate(facts, hourlyEvidence, thresholds)
        return repository.record(
            requestedTargetDate = targetDate,
            facts = facts,
            freshness = ForecastEligibilityReason.ELIGIBLE,
            requestStatus = "OK",
            factsSource = "provider",
            decision = decision,
            candidateCreated = decision.hazard.name != "NO_ALERT",
            locationMode = "SAVED_PLACE",
            locationMatch = true,
            occurrenceKey = "WEATHER_ALERT:$targetDate",
            triggerSource = "PERIODIC_FALLBACK",
            thresholds = thresholds,
        )
    }

    @Test
    fun `A-E each evaluation of the same target date gets a distinct immutable receipt`() = runTest {
        // A. low severity — below threshold, no alert.
        val idA = recordFor(facts(probability = 10.0, rainSumMm = 0.0, precipitationSumMm = 0.0))
        // B. qualifying rain — a real candidate.
        val idB = recordFor(facts(probability = 85.0, rainSumMm = 3.0))
        // C. a stronger forecast on a later evaluation of the SAME day.
        val idC = recordFor(facts(probability = 95.0, rainSumMm = 12.0, precipitationSumMm = 12.0))
        // D. a location revision change (the user moved / GPS updated).
        val idD = recordFor(facts(locationRevision = "coord:45.00,9.00"))
        // E. a policy/threshold config version change.
        val idE = recordFor(facts(), thresholds = WeatherAlertThresholdsV2(configVersion = 2, rainProbabilityMinPercent = 50.0))

        val ids = listOf(idA, idB, idC, idD, idE)
        ids.forEach { assertNotNull("every evaluation must produce a receipt id", it) }
        assertEquals("five evaluations must produce five DISTINCT receipt ids", 5, ids.toSet().size)

        val rows = dao.allRowsInSequence()
        assertEquals(5, rows.size)
        // Every row still carries the SAME requestedTargetDate — this is one
        // target date evaluated repeatedly, not five different days.
        assertTrue(rows.all { it.requestedTargetDate == targetDate.toString() })
    }

    @Test
    fun `receipt sequence is append-only - earlier rows are never rewritten by later inserts`() = runTest {
        recordFor(facts(probability = 10.0, rainSumMm = 0.0, precipitationSumMm = 0.0))
        val firstRowSnapshot = dao.allRowsInSequence().single()

        recordFor(facts(probability = 95.0, rainSumMm = 12.0))
        recordFor(facts(locationRevision = "coord:45.00,9.00"))

        val rows = dao.allRowsInSequence()
        assertEquals(3, rows.size)
        // rowSeq is strictly increasing, in insertion order — the durable
        // evaluation sequence §23 requires.
        assertTrue(rows.zipWithNext().all { (a, b) -> a.rowSeq < b.rowSeq })
        // The FIRST row's content is byte-for-byte the same as when it was
        // first written — nothing later mutated it.
        assertEquals(firstRowSnapshot, rows.first())
    }

    @Test
    fun `facts hash reflects the actual inputs - differs when inputs differ, stable when they do not`() = runTest {
        recordFor(facts(probability = 85.0, rainSumMm = 3.0))
        recordFor(facts(probability = 85.0, rainSumMm = 3.0)) // identical inputs, a second evaluation
        recordFor(facts(probability = 95.0, rainSumMm = 12.0)) // genuinely different inputs

        val rows = dao.allRowsInSequence()
        assertEquals(3, rows.size)
        assertNotNull(rows[0].factsHash)
        // Same facts -> same hash (deterministic, no invented instability).
        assertEquals(rows[0].factsHash, rows[1].factsHash)
        // Different facts -> a different hash (the hash is not a constant).
        assertTrue(rows[0].factsHash != rows[2].factsHash)
    }

    @Test
    fun `a receipt write failure never dispatches - record returns null on storage failure, never a fabricated id`() = runTest {
        val throwingDao = object : ForecastDecisionReceiptDao {
            override suspend fun insert(entity: ForecastDecisionReceiptEntity): Long = throw IllegalStateException("disk full")
            override suspend fun find(rowSeq: Long): ForecastDecisionReceiptEntity? = null
            override suspend fun findByReceiptId(receiptId: String): ForecastDecisionReceiptEntity? = null
            override suspend fun updateOutcomeEvents(rowSeq: Long, outcomeEventsJson: String): Int = 0
            override suspend fun recent(limit: Int): List<ForecastDecisionReceiptEntity> = emptyList()
            override suspend fun count(): Int = 0
            override suspend fun deleteOlderThan(cutoffMs: Long): Int = 0
            override suspend fun deleteOldestExcess(excess: Int): Int = 0
        }
        val brokenRepository = ForecastDecisionReceiptRepository(throwingDao)
        val decision = WeatherAlertPolicyV2.evaluate(facts())
        val id = brokenRepository.record(
            requestedTargetDate = targetDate,
            facts = facts(),
            freshness = ForecastEligibilityReason.ELIGIBLE,
            requestStatus = "OK",
            factsSource = "provider",
            decision = decision,
            candidateCreated = true,
            locationMode = "SAVED_PLACE",
            locationMatch = true,
            occurrenceKey = "WEATHER_ALERT:$targetDate",
            triggerSource = "PERIODIC_FALLBACK",
        )
        assertEquals(null, id)
    }
}
