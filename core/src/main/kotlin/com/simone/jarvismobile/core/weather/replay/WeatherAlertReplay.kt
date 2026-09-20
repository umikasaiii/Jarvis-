package com.simone.jarvismobile.core.weather.replay

import com.simone.jarvismobile.core.weather.ForecastEligibilityReason
import com.simone.jarvismobile.core.weather.ForecastFacts
import com.simone.jarvismobile.core.weather.ForecastFactsHash
import com.simone.jarvismobile.core.weather.HourlyPrecipitationEvidence
import com.simone.jarvismobile.core.weather.WeatherAlertDecisionV2
import com.simone.jarvismobile.core.weather.WeatherAlertFreshnessPolicyV2
import com.simone.jarvismobile.core.weather.WeatherAlertPolicyV2
import com.simone.jarvismobile.core.weather.WeatherAlertThresholdsV2
import com.simone.jarvismobile.core.weather.WeatherCategory
import com.simone.jarvismobile.core.weather.WeatherDecisionReason
import com.simone.jarvismobile.core.weather.WeatherHazard
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D.1 §5/§6/§35. The minimal DETERMINISTIC OFFLINE replay harness
 * for Work Package E — reuses the REAL, ALREADY-PRODUCTION pure functions
 * ([WeatherAlertFreshnessPolicyV2.evaluate]/[WeatherAlertPolicyV2.evaluate]),
 * never a copy/reimplementation in Python or a second parallel engine (§5's
 * explicit constraint). This module is pure `:core` Kotlin/JVM — no Room, no
 * Android, no network, no LLM, no [android.content.Context],
 * [com.simone.jarvismobile.context.ContextEngine], or
 * [com.simone.jarvismobile.proactive.ProactiveOccurrenceStore] dependency
 * anywhere in its call graph (verified by this file living entirely inside
 * `:core`, which cannot even IMPORT any of those `app/`-module classes).
 *
 * [WeatherAlertReplay.evaluate] never claims a production occurrence, never
 * notifies, never consumes a production budget, and never touches
 * [ForecastEligibilityReason]/[WeatherAlertDecisionV2] via anything but the
 * exact production entry points — it is the SAME decision production would
 * have made, computed offline from a fixture instead of a live fetch. All
 * "now" comes from [ReplayFixture.evaluationInstant], explicit in every
 * fixture — never [Instant.now]/the system clock — so a replay run is
 * reproducible byte-for-byte no matter when it is actually executed.
 */
const val REPLAY_SCHEMA_VERSION: Int = 1

/**
 * §6 — the versioned fixture schema. [facts] `null` simulates a fixture
 * where the forecast fetch itself never produced usable facts (mirrors
 * production's own `WeatherRequestOutcome.Failure`/`null` path) — the
 * replay still runs, producing [ReplayClassification.INVALID] rather than
 * failing outright, exactly like production degrades honestly instead of
 * crashing.
 */
@Serializable
data class ReplayFixture(
    val fixtureId: String,
    val schemaVersion: Int = REPLAY_SCHEMA_VERSION,
    val requestedTargetDate: String,
    val evaluationInstant: String,
    val currentLocationRevisionOverride: String? = null,
    val facts: ReplayForecastFacts?,
    val hourlyEvidence: List<ReplayHourlyEvidence> = emptyList(),
    val thresholdConfigVersion: Int = 1,
    val rainProbabilityMinPercent: Double = 70.0,
    val rainLiquidMinMm: Double = 1.0,
    val rainLiquidLowMinMm: Double = 0.5,
    val rainMinHours: Double = 2.0,
    val heavyDailyAmountMm: Double = 10.0,
    val stormProbabilityMinPercent: Double = 70.0,
    val stormLiquidMinMm: Double = 0.2,
    /** §6 — "optional observed outcome label for qualification". Whether a meaningful hazard actually happened, for later TP/FP/TN/FN aggregation ([WeatherAlertReplay.aggregate]) — never used by [WeatherAlertReplay.evaluate] itself, which never sees this field. */
    val observedOutcome: ObservedOutcome? = null,
)

@Serializable
enum class ObservedOutcome { HAZARD_OCCURRED, NO_HAZARD_OCCURRED }

/** A JSON-friendly, string-dated mirror of [ForecastFacts] — see [toDomain]. */
@Serializable
data class ReplayForecastFacts(
    val targetDate: String,
    val providerTimezone: String?,
    val locationRevision: String,
    val fetchedAt: String,
    val rawWeatherCode: Int?,
    val category: String? = null,
    val precipitationSumMm: Double?,
    val rainSumMm: Double?,
    val showersSumMm: Double?,
    val snowfallSumCm: Double?,
    val precipitationProbabilityMaxPercent: Double?,
    val precipitationHours: Double?,
) {
    fun toDomain(): ForecastFacts = ForecastFacts(
        targetDate = LocalDate.parse(targetDate),
        providerTimezone = providerTimezone,
        locationRevision = locationRevision,
        fetchedAt = Instant.parse(fetchedAt),
        rawWeatherCode = rawWeatherCode,
        category = category?.let { name -> runCatching { WeatherCategory.valueOf(name) }.getOrNull() },
        precipitationSumMm = precipitationSumMm,
        rainSumMm = rainSumMm,
        showersSumMm = showersSumMm,
        snowfallSumCm = snowfallSumCm,
        precipitationProbabilityMaxPercent = precipitationProbabilityMaxPercent,
        precipitationHours = precipitationHours,
    )
}

/** A JSON-friendly, string-dated mirror of [HourlyPrecipitationEvidence]. */
@Serializable
data class ReplayHourlyEvidence(
    val date: String,
    val hour: Int,
    val rawWeatherCode: Int?,
    val precipitationProbabilityPercent: Double?,
    val rainMm: Double?,
    val showersMm: Double?,
    val precipitationMm: Double?,
) {
    fun toDomain(): HourlyPrecipitationEvidence = HourlyPrecipitationEvidence(
        date = LocalDate.parse(date), hour = hour, rawWeatherCode = rawWeatherCode,
        precipitationProbabilityPercent = precipitationProbabilityPercent,
        rainMm = rainMm, showersMm = showersMm, precipitationMm = precipitationMm,
    )
}

/**
 * §5's "candidate/no-alert/unknown" plus [INVALID] for the case the real
 * production `evaluateWeatherAlert()` also has — data too stale/mismatched/
 * missing to ever reach the policy at all. [CANDIDATE]/[NO_ALERT] are the
 * only two classes ever fed into [WeatherAlertReplay.aggregate]'s TP/FP/
 * TN/FN counts — [UNKNOWN]/[INVALID] are excluded (§5: "do not invent a
 * universal accuracy score" — a policy that honestly declined to guess is
 * not a wrong prediction, it is a non-prediction).
 */
enum class ReplayClassification { CANDIDATE, NO_ALERT, UNKNOWN, INVALID }

/** §6's required per-evaluation output — every field is a bounded enum/hash/id, never a rendered message. */
@Serializable
data class ReplayResult(
    val fixtureId: String,
    val policyVersion: Int,
    val thresholdConfigVersion: Int,
    val factsHash: String?,
    val requestedTargetDate: String,
    val freshnessResult: String,
    val hazard: String?,
    val decisionReason: String?,
    val classification: String,
    val usedHourlyEvidenceCount: Int,
    val precipitationFallbackUsed: Boolean,
    val observedOutcome: String?,
)

/**
 * §6's optional aggregate — computed only over
 * [ReplayClassification.CANDIDATE]/[ReplayClassification.NO_ALERT] results
 * that also carry an [ObservedOutcome]. `null` ratios mean the denominator
 * was zero, never a fabricated 0.0/1.0.
 *
 * § WORK PACKAGE E §21 — [excludedFromAggregate] is broken down by REASON
 * ([unknownCount]/[invalidDataCount]/[staleDataCount]/
 * [locationMismatchCount]/[noSourceCount]/[unlabeledCount], each with its
 * own rate) so an UNKNOWN policy decision, a stale fetch, a location
 * mismatch, a missing source, and a genuinely still-unlabeled prospective
 * evaluation are never collapsed into one opaque number — §21's explicit
 * "do not hide UNKNOWN cases by counting them as TN" and "do not report
 * one vague accuracy number as the primary metric". These six counts plus
 * [truePositive]/[falsePositive]/[trueNegative]/[falseNegative] always sum
 * to exactly [evaluatedCount] — a non-overlapping partition of every
 * result, verified by test.
 */
@Serializable
data class ReplayAggregate(
    val evaluatedCount: Int,
    val truePositive: Int,
    val falsePositive: Int,
    val trueNegative: Int,
    val falseNegative: Int,
    val excludedFromAggregate: Int,
    val precision: Double?,
    val recall: Double?,
    val falsePositiveRate: Double?,
    val falseNegativeRate: Double?,
    val unknownCount: Int = 0,
    val invalidDataCount: Int = 0,
    val staleDataCount: Int = 0,
    val locationMismatchCount: Int = 0,
    val noSourceCount: Int = 0,
    val unlabeledCount: Int = 0,
    val unknownRate: Double? = null,
    val invalidDataRate: Double? = null,
    val staleDataRate: Double? = null,
    val locationMismatchRate: Double? = null,
    val noSourceRate: Double? = null,
)

object WeatherAlertReplay {
    private val json = Json { encodeDefaults = true }

    /**
     * §5/§35 — the entire pipeline: `ForecastFacts validation ->
     * WeatherAlertFreshnessPolicyV2 -> WeatherAlertPolicyV2 -> controlled
     * decision reason`, exactly as production's
     * `ProactiveManager.evaluateWeatherAlert()` runs it, minus every
     * Android/Room/dispatch/receipt concern that function also has. Same
     * function called twice with the same [fixture] always returns an
     * identical [ReplayResult] — no mutable state anywhere in this object.
     */
    fun evaluate(fixture: ReplayFixture): ReplayResult {
        val targetDate = LocalDate.parse(fixture.requestedTargetDate)
        val now = Instant.parse(fixture.evaluationInstant)
        val domainFacts = fixture.facts?.toDomain()
        val thresholds = WeatherAlertThresholdsV2(
            configVersion = fixture.thresholdConfigVersion,
            rainProbabilityMinPercent = fixture.rainProbabilityMinPercent,
            rainLiquidMinMm = fixture.rainLiquidMinMm,
            rainLiquidLowMinMm = fixture.rainLiquidLowMinMm,
            rainMinHours = fixture.rainMinHours,
            heavyDailyAmountMm = fixture.heavyDailyAmountMm,
            stormProbabilityMinPercent = fixture.stormProbabilityMinPercent,
            stormLiquidMinMm = fixture.stormLiquidMinMm,
        )

        // Same production function, null-facts-safe by its own contract —
        // never a second null-check reimplemented here.
        val freshness = WeatherAlertFreshnessPolicyV2.evaluate(
            facts = domainFacts,
            expectedTargetDate = targetDate,
            currentLocationRevision = fixture.currentLocationRevisionOverride ?: domainFacts?.locationRevision.orEmpty(),
            now = now,
        )

        if (freshness != ForecastEligibilityReason.ELIGIBLE) {
            return ReplayResult(
                fixtureId = fixture.fixtureId,
                policyVersion = WeatherAlertPolicyV2.POLICY_VERSION,
                thresholdConfigVersion = thresholds.configVersion,
                factsHash = domainFacts?.let { ForecastFactsHash.of(it) },
                requestedTargetDate = fixture.requestedTargetDate,
                freshnessResult = freshness.name,
                hazard = null,
                decisionReason = null,
                classification = ReplayClassification.INVALID.name,
                usedHourlyEvidenceCount = 0,
                precipitationFallbackUsed = false,
                observedOutcome = fixture.observedOutcome?.name,
            )
        }

        // ELIGIBLE is only ever returned for non-null facts (see
        // WeatherAlertFreshnessPolicyV2's own MISSING_FACTS-on-null
        // guarantee) — mirrors the identical `facts!!` production already
        // relies on in ProactiveManager.evaluateWeatherAlert() after the
        // same check.
        val facts = domainFacts!!
        val hourlyEvidence = fixture.hourlyEvidence.map { it.toDomain() }
        val decision = WeatherAlertPolicyV2.evaluate(facts, hourlyEvidence, thresholds)

        return ReplayResult(
            fixtureId = fixture.fixtureId,
            policyVersion = WeatherAlertPolicyV2.POLICY_VERSION,
            thresholdConfigVersion = thresholds.configVersion,
            factsHash = ForecastFactsHash.of(facts),
            requestedTargetDate = fixture.requestedTargetDate,
            freshnessResult = freshness.name,
            hazard = decision.hazard.name,
            decisionReason = decision.reason.name,
            classification = classify(decision).name,
            usedHourlyEvidenceCount = decision.usedHourlyEvidenceCount,
            precipitationFallbackUsed = decision.precipitationFallbackUsed,
            observedOutcome = fixture.observedOutcome?.name,
        )
    }

    fun evaluateAll(fixtures: List<ReplayFixture>): List<ReplayResult> = fixtures.map(::evaluate)

    /**
     * §6 — TP/FP/TN/FN over the [ReplayClassification.CANDIDATE]/
     * [ReplayClassification.NO_ALERT] results that also carry an
     * [ObservedOutcome] (via [ReplayResult.observedOutcome]/
     * [ReplayFixture.observedOutcome], threaded through unchanged).
     * [ReplayClassification.UNKNOWN]/[ReplayClassification.INVALID] and any
     * result missing an observed outcome are counted in
     * [ReplayAggregate.excludedFromAggregate], never forced into a
     * TP/FP/TN/FN bucket. No accuracy score, F1, or acceptance threshold is
     * computed here — Work Package E decides what (if anything) to do with
     * these four numbers.
     */
    fun aggregate(results: List<ReplayResult>): ReplayAggregate {
        var tp = 0
        var fp = 0
        var tn = 0
        var fn = 0
        var excluded = 0
        var unknownCount = 0
        var invalidDataCount = 0
        var staleDataCount = 0
        var locationMismatchCount = 0
        var noSourceCount = 0
        var unlabeledCount = 0
        for (r in results) {
            val truth = r.observedOutcome
            val predictedPositive = r.classification == ReplayClassification.CANDIDATE.name
            val predictedNegative = r.classification == ReplayClassification.NO_ALERT.name
            if (truth == null || (!predictedPositive && !predictedNegative)) {
                excluded++
                // § §21 — a non-overlapping breakdown of WHY this result was
                // excluded, checked in the same priority order production's
                // own freshness gate applies (a freshness failure always
                // means the policy never ran, regardless of classification).
                when (r.freshnessResult) {
                    ForecastEligibilityReason.MISSING_FACTS.name -> noSourceCount++
                    ForecastEligibilityReason.LOCATION_MISMATCH.name -> locationMismatchCount++
                    ForecastEligibilityReason.STALE.name -> staleDataCount++
                    ForecastEligibilityReason.DATE_MISMATCH.name, ForecastEligibilityReason.FUTURE_TIMESTAMP.name -> invalidDataCount++
                    else -> if (r.classification == ReplayClassification.UNKNOWN.name) {
                        unknownCount++
                    } else {
                        // freshness was ELIGIBLE and the policy reached a
                        // real CANDIDATE/NO_ALERT decision, but no ground
                        // truth label exists yet — a genuinely pending
                        // prospective shadow evaluation, not a data defect.
                        unlabeledCount++
                    }
                }
                continue
            }
            val truthPositive = truth == ObservedOutcome.HAZARD_OCCURRED.name
            when {
                predictedPositive && truthPositive -> tp++
                predictedPositive && !truthPositive -> fp++
                predictedNegative && !truthPositive -> tn++
                else -> fn++
            }
        }
        val total = results.size
        return ReplayAggregate(
            evaluatedCount = total,
            truePositive = tp,
            falsePositive = fp,
            trueNegative = tn,
            falseNegative = fn,
            excludedFromAggregate = excluded,
            precision = ratio(tp, tp + fp),
            recall = ratio(tp, tp + fn),
            falsePositiveRate = ratio(fp, fp + tn),
            falseNegativeRate = ratio(fn, fn + tp),
            unknownCount = unknownCount,
            invalidDataCount = invalidDataCount,
            staleDataCount = staleDataCount,
            locationMismatchCount = locationMismatchCount,
            noSourceCount = noSourceCount,
            unlabeledCount = unlabeledCount,
            unknownRate = ratio(unknownCount, total),
            invalidDataRate = ratio(invalidDataCount, total),
            staleDataRate = ratio(staleDataCount, total),
            locationMismatchRate = ratio(locationMismatchCount, total),
            noSourceRate = ratio(noSourceCount, total),
        )
    }

    /** §6 — one JSON object per line, machine-readable, deterministic field order (`encodeDefaults = true`). */
    fun toJsonLines(results: List<ReplayResult>): String = results.joinToString("\n") { json.encodeToString(it) }

    fun toJson(aggregate: ReplayAggregate): String = json.encodeToString(aggregate)

    private fun classify(decision: WeatherAlertDecisionV2): ReplayClassification = when {
        decision.hazard != WeatherHazard.NO_ALERT -> ReplayClassification.CANDIDATE
        decision.reason == WeatherDecisionReason.UNKNOWN_STORM_CONFIDENCE ||
            decision.reason == WeatherDecisionReason.UNKNOWN_MISSING_FACTS ||
            decision.reason == WeatherDecisionReason.UNKNOWN_INVALID_DATA -> ReplayClassification.UNKNOWN
        else -> ReplayClassification.NO_ALERT
    }

    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator
}
