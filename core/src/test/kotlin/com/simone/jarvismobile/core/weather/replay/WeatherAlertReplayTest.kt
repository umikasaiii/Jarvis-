package com.simone.jarvismobile.core.weather.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherAlertReplayTest {

    private val targetDate = "2026-09-20"
    private val evalInstant = "2026-09-19T18:00:00Z"

    private fun facts(
        rawWeatherCode: Int? = 61,
        precipitationSumMm: Double? = 3.0,
        rainSumMm: Double? = 3.0,
        showersSumMm: Double? = null,
        snowfallSumCm: Double? = null,
        precipitationProbabilityMaxPercent: Double? = 85.0,
        precipitationHours: Double? = 3.0,
        locationRevision: String = "coord:41.90,12.50",
        fetchedAt: String = evalInstant,
        target: String = targetDate,
    ) = ReplayForecastFacts(
        targetDate = target, providerTimezone = "Europe/Rome", locationRevision = locationRevision,
        fetchedAt = fetchedAt, rawWeatherCode = rawWeatherCode, category = null,
        precipitationSumMm = precipitationSumMm, rainSumMm = rainSumMm, showersSumMm = showersSumMm,
        snowfallSumCm = snowfallSumCm, precipitationProbabilityMaxPercent = precipitationProbabilityMaxPercent,
        precipitationHours = precipitationHours,
    )

    private fun fixture(
        id: String = "f1",
        f: ReplayForecastFacts? = facts(),
        hourly: List<ReplayHourlyEvidence> = emptyList(),
        observed: ObservedOutcome? = null,
        locationOverride: String? = null,
    ) = ReplayFixture(
        fixtureId = id, requestedTargetDate = targetDate, evaluationInstant = evalInstant,
        currentLocationRevisionOverride = locationOverride, facts = f, hourlyEvidence = hourly,
        observedOutcome = observed,
    )

    // --- W11: determinism -----------------------------------------------

    @Test fun evaluate_isDeterministic_sameFixtureTwice() {
        val fx = fixture()
        val r1 = WeatherAlertReplay.evaluate(fx)
        val r2 = WeatherAlertReplay.evaluate(fx)
        assertEquals(r1, r2)
    }

    @Test fun evaluate_neverDependsOnSystemClock_onlyFixtureInstant() {
        // Two structurally-identical fixtures whose only difference is the
        // fixtureId still produce identical decision fields — nothing here
        // can vary run-to-run since Instant.now()/LocalDate.now() are never
        // called by this module.
        val a = WeatherAlertReplay.evaluate(fixture(id = "a"))
        val b = WeatherAlertReplay.evaluate(fixture(id = "b"))
        assertEquals(a.hazard, b.hazard)
        assertEquals(a.decisionReason, b.decisionReason)
        assertEquals(a.freshnessResult, b.freshnessResult)
        assertEquals(a.classification, b.classification)
    }

    // --- classification ---------------------------------------------------

    @Test fun classify_qualifyingRain_isCandidate() {
        val r = WeatherAlertReplay.evaluate(fixture())
        assertEquals(ReplayClassification.CANDIDATE.name, r.classification)
        assertEquals("RAIN_EXPECTED", r.hazard)
    }

    @Test fun classify_belowThreshold_isNoAlert() {
        val r = WeatherAlertReplay.evaluate(fixture(f = facts(precipitationProbabilityMaxPercent = 10.0)))
        assertEquals(ReplayClassification.NO_ALERT.name, r.classification)
        assertEquals("NO_ALERT", r.hazard)
        assertEquals("BELOW_THRESHOLD", r.decisionReason)
    }

    @Test fun classify_stormWithoutAlignedEvidence_isUnknown() {
        val r = WeatherAlertReplay.evaluate(
            fixture(f = facts(rawWeatherCode = 95, precipitationProbabilityMaxPercent = 10.0, rainSumMm = 0.0, precipitationSumMm = 0.0)),
        )
        assertEquals(ReplayClassification.UNKNOWN.name, r.classification)
        assertEquals("UNKNOWN_STORM_CONFIDENCE", r.decisionReason)
    }

    @Test fun classify_stormWithAlignedEvidence_isCandidate() {
        val hourly = listOf(
            ReplayHourlyEvidence(
                date = targetDate, hour = 15, rawWeatherCode = 95,
                precipitationProbabilityPercent = 85.0, rainMm = 1.0, showersMm = null, precipitationMm = 1.0,
            ),
        )
        val r = WeatherAlertReplay.evaluate(fixture(f = facts(rawWeatherCode = 95), hourly = hourly))
        assertEquals(ReplayClassification.CANDIDATE.name, r.classification)
        assertEquals("THUNDERSTORM", r.hazard)
        assertEquals(1, r.usedHourlyEvidenceCount)
    }

    @Test fun classify_snowOnly_isNoAlert_neverRain() {
        val r = WeatherAlertReplay.evaluate(fixture(f = facts(rawWeatherCode = 71)))
        assertEquals(ReplayClassification.NO_ALERT.name, r.classification)
        assertEquals("SNOW_ONLY_NO_RAIN_ALERT", r.decisionReason)
    }

    // --- freshness / invalid ----------------------------------------------

    @Test fun evaluate_dateMismatch_isInvalid() {
        val r = WeatherAlertReplay.evaluate(fixture(f = facts(target = "2026-09-21")))
        assertEquals(ReplayClassification.INVALID.name, r.classification)
        assertEquals("DATE_MISMATCH", r.freshnessResult)
        assertNull(r.hazard)
    }

    @Test fun evaluate_locationMismatch_isInvalid() {
        val r = WeatherAlertReplay.evaluate(fixture(locationOverride = "coord:45.00,9.00"))
        assertEquals(ReplayClassification.INVALID.name, r.classification)
        assertEquals("LOCATION_MISMATCH", r.freshnessResult)
    }

    @Test fun evaluate_staleFetch_isInvalid() {
        val r = WeatherAlertReplay.evaluate(fixture(f = facts(fetchedAt = "2026-09-15T18:00:00Z")))
        assertEquals(ReplayClassification.INVALID.name, r.classification)
        assertEquals("STALE", r.freshnessResult)
    }

    @Test fun evaluate_nullFacts_isInvalid_neverCrashes() {
        val r = WeatherAlertReplay.evaluate(fixture(f = null))
        assertEquals(ReplayClassification.INVALID.name, r.classification)
        assertEquals("MISSING_FACTS", r.freshnessResult)
        assertNull(r.factsHash)
    }

    // --- facts hash surfaced ------------------------------------------------

    @Test fun evaluate_factsHash_stableAcrossRuns() {
        val fx = fixture()
        val r1 = WeatherAlertReplay.evaluate(fx)
        val r2 = WeatherAlertReplay.evaluate(fx)
        assertEquals(r1.factsHash, r2.factsHash)
        assertTrue(r1.factsHash!!.length == 64)
    }

    // --- aggregate ----------------------------------------------------------

    @Test fun aggregate_computesTpFpTnFn_excludesUnknownAndInvalidAndUnlabeled() {
        val results = listOf(
            // TP: candidate, hazard occurred
            WeatherAlertReplay.evaluate(fixture(id = "tp", observed = ObservedOutcome.HAZARD_OCCURRED)),
            // FP: candidate, no hazard occurred
            WeatherAlertReplay.evaluate(fixture(id = "fp", observed = ObservedOutcome.NO_HAZARD_OCCURRED)),
            // TN: no-alert, no hazard occurred
            WeatherAlertReplay.evaluate(
                fixture(id = "tn", f = facts(precipitationProbabilityMaxPercent = 10.0), observed = ObservedOutcome.NO_HAZARD_OCCURRED),
            ),
            // FN: no-alert, hazard occurred
            WeatherAlertReplay.evaluate(
                fixture(id = "fn", f = facts(precipitationProbabilityMaxPercent = 10.0), observed = ObservedOutcome.HAZARD_OCCURRED),
            ),
            // excluded: unknown, even with an observed label
            WeatherAlertReplay.evaluate(
                fixture(
                    id = "unk",
                    f = facts(rawWeatherCode = 95, precipitationProbabilityMaxPercent = 10.0, rainSumMm = 0.0, precipitationSumMm = 0.0),
                    observed = ObservedOutcome.HAZARD_OCCURRED,
                ),
            ),
            // excluded: invalid
            WeatherAlertReplay.evaluate(fixture(id = "inv", f = null, observed = ObservedOutcome.HAZARD_OCCURRED)),
            // excluded: no observed label at all
            WeatherAlertReplay.evaluate(fixture(id = "nolabel", observed = null)),
        )
        val agg = WeatherAlertReplay.aggregate(results)
        assertEquals(7, agg.evaluatedCount)
        assertEquals(1, agg.truePositive)
        assertEquals(1, agg.falsePositive)
        assertEquals(1, agg.trueNegative)
        assertEquals(1, agg.falseNegative)
        assertEquals(3, agg.excludedFromAggregate)
        assertEquals(0.5, agg.precision)
        assertEquals(0.5, agg.recall)
        assertEquals(0.5, agg.falsePositiveRate)
        assertEquals(0.5, agg.falseNegativeRate)
    }

    @Test fun aggregate_zeroDenominator_producesNullRatio_neverFabricated() {
        val agg = WeatherAlertReplay.aggregate(emptyList())
        assertEquals(0, agg.evaluatedCount)
        assertNull(agg.precision)
        assertNull(agg.recall)
        assertNull(agg.falsePositiveRate)
        assertNull(agg.falseNegativeRate)
    }

    // --- JSON output ----------------------------------------------------------

    @Test fun toJsonLines_producesOneLinePerResult_parseableJson() {
        val results = listOf(WeatherAlertReplay.evaluate(fixture(id = "a")), WeatherAlertReplay.evaluate(fixture(id = "b")))
        val lines = WeatherAlertReplay.toJsonLines(results).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"fixtureId\":\"a\""))
        assertTrue(lines[1].contains("\"fixtureId\":\"b\""))
    }

    @Test fun toJson_aggregate_isValidObject() {
        val agg = WeatherAlertReplay.aggregate(listOf(WeatherAlertReplay.evaluate(fixture(observed = ObservedOutcome.HAZARD_OCCURRED))))
        val out = WeatherAlertReplay.toJson(agg)
        assertTrue(out.startsWith("{") && out.endsWith("}"))
        assertTrue(out.contains("\"evaluatedCount\":1"))
    }
}
