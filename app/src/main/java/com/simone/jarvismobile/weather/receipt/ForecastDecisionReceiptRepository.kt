package com.simone.jarvismobile.weather.receipt

import android.util.Log
import com.simone.jarvismobile.BuildConfig
import com.simone.jarvismobile.core.weather.ForecastEligibilityReason
import com.simone.jarvismobile.core.weather.ForecastFacts
import com.simone.jarvismobile.core.weather.ForecastFactsHash
import com.simone.jarvismobile.core.weather.WeatherAlertDecisionV2
import com.simone.jarvismobile.core.weather.WeatherAlertPolicyV2
import com.simone.jarvismobile.core.weather.WeatherAlertThresholdsV2
import com.simone.jarvismobile.core.weather.WeatherReceiptRetentionPolicy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D §22. The single writer for [ForecastDecisionReceiptEntity] —
 * every production [WeatherAlertPolicyV2] evaluation calls [record] exactly
 * once, BEFORE any notification dispatch decision is acted on (§22 — "every
 * notification attempt references a receipt committed BEFORE dispatch").
 *
 * [RECEIPT WRITE FAILURE → NO ALERT DISPATCH] is enforced by the CALLER
 * ([com.simone.jarvismobile.proactive.ProactiveManager]): [record] returns
 * null on any failure (never throws — a `runCatching` boundary here, same
 * discipline as every other storage write in this codebase that must not
 * crash a background evaluation), and the caller treats a null receipt id
 * exactly like "no candidate" — never dispatching without one.
 */
@Singleton
class ForecastDecisionReceiptRepository @Inject constructor(
    private val dao: ForecastDecisionReceiptDao,
) {
    /**
     * Writes one immutable receipt for a completed [WeatherAlertPolicyV2]
     * evaluation. [facts] may be null when the evaluation could not even
     * reach the policy (e.g. a request-scoped fetch failure) — every field
     * that would have come from it degrades to `null`/an honest placeholder
     * rather than a crash or an invented value. Returns the receipt's
     * [ForecastDecisionReceiptEntity.receiptId] on success, `null` on any
     * storage failure — see the class doc comment for what the caller must
     * do with that.
     */
    @Suppress("LongParameterList")
    suspend fun record(
        requestedTargetDate: LocalDate,
        facts: ForecastFacts?,
        freshness: ForecastEligibilityReason,
        requestStatus: String,
        factsSource: String,
        decision: WeatherAlertDecisionV2?,
        candidateCreated: Boolean,
        locationMode: String,
        locationMatch: Boolean?,
        occurrenceKey: String?,
        triggerSource: String?,
        notificationNamespace: String? = null,
        thresholds: WeatherAlertThresholdsV2 = WeatherAlertThresholdsV2(),
    ): String? = runCatching {
        val nowMs = System.currentTimeMillis()
        val receiptId = UUID.randomUUID().toString()
        val fieldsMissing = buildList {
            if (facts != null) {
                if (facts.rawWeatherCode == null) add("rawWeatherCode")
                if (facts.precipitationSumMm == null) add("precipitationSumMm")
                if (facts.rainSumMm == null) add("rainSumMm")
                if (facts.showersSumMm == null) add("showersSumMm")
                if (facts.snowfallSumCm == null) add("snowfallSumCm")
                if (facts.precipitationProbabilityMaxPercent == null) add("precipitationProbabilityMaxPercent")
                if (facts.precipitationHours == null) add("precipitationHours")
            }
        }.joinToString(",")
        val entity = ForecastDecisionReceiptEntity(
            receiptId = receiptId,
            schemaVersion = SCHEMA_VERSION,
            appBuildId = runCatching { BuildConfig.BUILD_ID }.getOrNull(),
            policyVersion = WeatherAlertPolicyV2.POLICY_VERSION,
            thresholdConfigVersion = thresholds.configVersion,
            factsHash = facts?.let { runCatching { ForecastFactsHash.of(it) }.getOrNull() },
            evaluatedAtUtcMs = nowMs,
            requestedTargetDate = requestedTargetDate.toString(),
            providerTargetDate = facts?.targetDate?.toString(),
            forecastTimezone = facts?.providerTimezone,
            fetchedAtMs = facts?.fetchedAt?.toEpochMilli(),
            forecastAgeMs = facts?.fetchedAt?.let { Instant.now().toEpochMilli() - it.toEpochMilli() },
            // § §24 — Open-Meteo never supplies a real provider model run
            // timestamp in the fields this app requests; never invented.
            providerRunTimestamp = null,
            providerId = "open-meteo",
            endpointKind = "dated_daily",
            locationRevision = facts?.locationRevision,
            locationMode = locationMode,
            locationMatch = locationMatch,
            rawWeatherCode = facts?.rawWeatherCode,
            category = facts?.category?.name,
            precipitationSumMm = facts?.precipitationSumMm,
            rainSumMm = facts?.rainSumMm,
            showersSumMm = facts?.showersSumMm,
            snowfallSumCm = facts?.snowfallSumCm,
            precipitationProbabilityMaxPercent = facts?.precipitationProbabilityMaxPercent,
            precipitationHours = facts?.precipitationHours,
            fieldsMissing = fieldsMissing,
            dateMatch = facts != null && facts.targetDate == requestedTargetDate,
            freshnessResult = freshness.name,
            requestStatus = requestStatus,
            factsSource = factsSource,
            hazard = decision?.hazard?.name,
            decisionReason = decision?.reason?.name ?: "NOT_EVALUATED",
            candidateCreated = candidateCreated,
            precipitationFallbackUsed = decision?.precipitationFallbackUsed ?: false,
            usedHourlyEvidenceCount = decision?.usedHourlyEvidenceCount ?: 0,
            occurrenceKey = occurrenceKey,
            triggerSource = triggerSource,
            notificationNamespace = notificationNamespace,
            dispatchAttemptId = null,
            outcomeEventsJson = "[]",
        )
        dao.insert(entity)
        receiptId
    }.onFailure { e ->
        Log.w(TAG, "forecast_decision_receipt_write_failed ${e.javaClass.simpleName}")
    }.getOrNull()

    /**
     * §27 — append-only outcome events (CLAIMED/PREFLIGHT_BLOCKED/
     * DISPATCH_INTENT/POSTED/UNKNOWN_EFFECT). See
     * [ForecastDecisionReceiptEntity]'s own doc comment for why this is a
     * bounded JSON field on the same immutable row rather than a second
     * table — a read-modify-write of ONLY that field, never mutating any
     * other column. Best-effort: a failure here never blocks the caller's
     * own dispatch flow (already decided by the time this is called).
     */
    suspend fun appendOutcomeEvent(receiptId: String, event: String) {
        runCatching {
            val entity = dao.findByReceiptId(receiptId) ?: return
            val nowMs = System.currentTimeMillis()
            val updated = if (entity.outcomeEventsJson.length > MAX_OUTCOME_EVENTS_JSON_CHARS) {
                // § §28 — bounded even within one row: never let a single
                // pathological receipt grow its events field unbounded.
                entity.outcomeEventsJson
            } else {
                val sep = if (entity.outcomeEventsJson == "[]") "" else ","
                entity.outcomeEventsJson.removeSuffix("]") + sep + "{\"event\":\"$event\",\"atMs\":$nowMs}]"
            }
            dao.updateOutcomeEvents(entity.rowSeq, updated)
        }.onFailure { e -> Log.w(TAG, "forecast_decision_receipt_append_failed ${e.javaClass.simpleName}") }
    }

    /**
     * § WORK PACKAGE D.1 §3 — bounded local retention: 90 days AND max 4096
     * detailed rows (§28), now genuinely called (see
     * [com.simone.jarvismobile.proactive.ProactiveWorker], the EXISTING
     * hourly proactivity maintenance tick — no second scheduler was
     * created). The cutoff/excess ARITHMETIC is delegated to the pure,
     * tested [WeatherReceiptRetentionPolicy]; this function stays
     * responsible only for the I/O around it. A failure here is logged and
     * swallowed — retention is best-effort maintenance, never allowed to
     * propagate into (and therefore ever block) the proactivity tick that
     * happens to trigger it.
     *
     * §3 — occurrence/dedup independence: [com.simone.jarvismobile.proactive.ProactiveOccurrenceStore]
     * lives entirely in the SEPARATE `JarvisDatabase`
     * ([com.simone.jarvismobile.weather.receipt.WeatherDecisionDatabase] is
     * its own file) and never reads from [dao] — occurrence claim/dispatch
     * safety (Work Package A) does not depend on any receipt row surviving,
     * by construction, not merely by convention. See
     * `docs/DEVICE_TEST_WEATHER_ALERT.md`'s D.1 addendum for the manual
     * verification note.
     */
    suspend fun prune(retentionDays: Long = WeatherReceiptRetentionPolicy.DEFAULT_RETENTION_DAYS, maxRows: Int = WeatherReceiptRetentionPolicy.DEFAULT_MAX_ROWS) {
        runCatching {
            val cutoffMs = WeatherReceiptRetentionPolicy.cutoffMs(System.currentTimeMillis(), retentionDays)
            dao.deleteOlderThan(cutoffMs)
            val remaining = dao.count()
            val excess = WeatherReceiptRetentionPolicy.excessRowCount(remaining, maxRows)
            if (excess > 0) dao.deleteOldestExcess(excess)
        }.onFailure { e -> Log.w(TAG, "forecast_decision_receipt_prune_failed ${e.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "WeatherReceipt"
        const val SCHEMA_VERSION = 1
        const val MAX_OUTCOME_EVENTS_JSON_CHARS = 2000
    }
}
