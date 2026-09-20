package com.simone.jarvismobile.weather.receipt

import com.simone.jarvismobile.core.weather.WeatherAlertThresholdsV2
import com.simone.jarvismobile.core.weather.replay.REPLAY_SCHEMA_VERSION
import com.simone.jarvismobile.core.weather.replay.ReplayFixture
import com.simone.jarvismobile.core.weather.replay.ReplayForecastFacts
import com.simone.jarvismobile.core.weather.replay.WeatherObservedOutcome
import java.time.Instant

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE E §18/§19. Converts a real, already-written production
 * [ForecastDecisionReceiptEntity] into a [ReplayFixture] — the bridge that
 * lets prospective shadow capture (§18: "capture an immutable receipt
 * BEFORE tomorrow occurs, then, only after the target day has passed,
 * attach an observed-outcome label") reuse the SAME deterministic replay
 * harness (`core/weather/replay/WeatherAlertReplay.kt`) already built in
 * Work Package D.1, instead of a second data path.
 *
 * §19's data-integrity gate, enforced here, not asserted in a doc comment
 * elsewhere: a [ForecastDecisionReceiptEntity] genuinely IS "what was
 * available at the decision time" — it was written by
 * [ProactiveManager.evaluateWeatherAlert] the SAME evening the real fetch
 * happened, never reconstructed later from a more-current forecast. Lives
 * in `app/` (not `:core`) purely because [ForecastDecisionReceiptEntity] is
 * a Room entity `:core` cannot depend on — the conversion itself performs
 * no I/O and touches no database.
 */
object ForecastDecisionReceiptReplayBridge {

    /**
     * §19 — the ONLY threshold configuration production has ever evaluated
     * a receipt against is [WeatherAlertThresholdsV2]'s own defaults (see
     * [com.simone.jarvismobile.proactive.ProactiveManager
     * .evaluateWeatherAlert], which calls `WeatherAlertPolicyV2.evaluate`
     * with no threshold argument) — [ForecastDecisionReceiptEntity
     * .thresholdConfigVersion] is always `1` for a real production row
     * today. A receipt recorded under any OTHER config version is refused
     * (`null`) rather than replayed against guessed threshold numbers the
     * receipt itself never stored, per §19's "state this limitation
     * explicitly" instruction.
     */
    private const val KNOWN_THRESHOLD_CONFIG_VERSION = 1

    /**
     * Returns `null` when the receipt cannot be faithfully replayed:
     * - [ForecastDecisionReceiptEntity.thresholdConfigVersion] is not the
     *   one known, default configuration (see [KNOWN_THRESHOLD_CONFIG_VERSION]);
     * - [ForecastDecisionReceiptEntity.locationRevision]/[fetchedAtMs] are
     *   missing on a receipt whose [freshnessResult] claims ELIGIBLE — an
     *   internally inconsistent row that must never be silently guessed at.
     *
     * When [ForecastDecisionReceiptEntity.providerTargetDate]/[rawWeatherCode]/
     * etc. are absent (the fetch itself failed, or the provider never
     * returned this target date — MISSING_FACTS/DATE_MISMATCH territory),
     * this still returns a valid fixture with `facts = null` — replaying
     * that is itself meaningful evidence (it reproduces the exact
     * INVALID/no-source classification production reached that evening,
     * feeding §21's `noSourceRate`/etc. breakdown), never treated as an
     * unreplayable receipt.
     *
     * §19's honest limit on location-mismatch fidelity: a receipt stores
     * only [ForecastDecisionReceiptEntity.locationMatch] (a boolean), not
     * the two distinct location revisions that were compared — so
     * [ReplayFixture.currentLocationRevisionOverride] is always set to the
     * SAME value as `facts.locationRevision`, reproducing a match (never a
     * mismatch) by construction. A receipt whose original evaluation was
     * itself a `LOCATION_MISMATCH` is therefore replayed as if the
     * location matched — this bridge is not evidence for validating THAT
     * specific mismatch decision; it exists for the ELIGIBLE, decision-
     * bearing receipts §18's shadow protocol actually needs.
     */
    fun ForecastDecisionReceiptEntity.toReplayFixture(observedOutcome: WeatherObservedOutcome? = null): ReplayFixture? {
        if (thresholdConfigVersion != KNOWN_THRESHOLD_CONFIG_VERSION) return null

        val evaluationInstant = Instant.ofEpochMilli(evaluatedAtUtcMs).toString()
        val facts = buildFacts() ?: return ReplayFixture(
            fixtureId = receiptId,
            schemaVersion = REPLAY_SCHEMA_VERSION,
            requestedTargetDate = requestedTargetDate,
            evaluationInstant = evaluationInstant,
            currentLocationRevisionOverride = locationRevision,
            facts = null,
            observedOutcome = observedOutcome?.toAggregationTruth(),
        )

        val defaults = WeatherAlertThresholdsV2()
        return ReplayFixture(
            fixtureId = receiptId,
            schemaVersion = REPLAY_SCHEMA_VERSION,
            requestedTargetDate = requestedTargetDate,
            evaluationInstant = evaluationInstant,
            currentLocationRevisionOverride = locationRevision,
            facts = facts,
            thresholdConfigVersion = defaults.configVersion,
            rainProbabilityMinPercent = defaults.rainProbabilityMinPercent,
            rainLiquidMinMm = defaults.rainLiquidMinMm,
            rainLiquidLowMinMm = defaults.rainLiquidLowMinMm,
            rainMinHours = defaults.rainMinHours,
            heavyDailyAmountMm = defaults.heavyDailyAmountMm,
            stormProbabilityMinPercent = defaults.stormProbabilityMinPercent,
            stormLiquidMinMm = defaults.stormLiquidMinMm,
            observedOutcome = observedOutcome?.toAggregationTruth(),
        )
    }

    /** `null` when the receipt does not carry enough of the provider's own fields to reconstruct [ReplayForecastFacts] — mirrors a genuine fetch/no-source failure, never a partial guess. */
    private fun ForecastDecisionReceiptEntity.buildFacts(): ReplayForecastFacts? {
        val targetDate = providerTargetDate ?: return null
        val revision = locationRevision ?: return null
        val fetchedAtInstant = fetchedAtMs ?: return null
        return ReplayForecastFacts(
            targetDate = targetDate,
            providerTimezone = forecastTimezone,
            locationRevision = revision,
            fetchedAt = Instant.ofEpochMilli(fetchedAtInstant).toString(),
            rawWeatherCode = rawWeatherCode,
            category = category,
            precipitationSumMm = precipitationSumMm,
            rainSumMm = rainSumMm,
            showersSumMm = showersSumMm,
            snowfallSumCm = snowfallSumCm,
            precipitationProbabilityMaxPercent = precipitationProbabilityMaxPercent,
            precipitationHours = precipitationHours,
        )
    }
}
