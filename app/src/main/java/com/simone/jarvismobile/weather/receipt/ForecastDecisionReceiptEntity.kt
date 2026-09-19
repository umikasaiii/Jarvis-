package com.simone.jarvismobile.weather.receipt

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D §22-§28. One immutable row per production
 * [com.simone.jarvismobile.core.weather.WeatherAlertPolicyV2] evaluation —
 * candidate, no-alert, unknown, suppressed, or invalid. A NEW receipt per
 * evaluation, never a mutable rewrite (§23). [rowSeq] is the durable,
 * monotonically increasing evaluation sequence (§23) — Room's own
 * `autoGenerate` primary key, which SQLite already guarantees is strictly
 * increasing per insert, so no second counter is needed. [receiptId] is a
 * separate UUID for external/cross-system reference (§23's own field),
 * distinct from the row's storage identity.
 *
 * §28's "append-only outcome events" are folded into [outcomeEventsJson] — a
 * small, bounded JSON array (`[{"event":"CLAIMED","atMs":...}]`) on this
 * same immutable row — a disclosed engineering simplification, not a
 * literal second SQL table: each receipt is already one-per-evaluation and
 * immutable, so appending to this field (read-modify-write of the SAME row,
 * done only by [ForecastDecisionReceiptRepository.appendOutcomeEvent]) still
 * respects append-only semantics per evaluation without adding DAO/migration
 * surface for a rarely-queried sub-table.
 *
 * §25 PRIVACY: [locationRevision] is the SAME opaque
 * [com.simone.jarvismobile.core.weather.WeatherLocationKey.asCacheTag] tag
 * used everywhere else in this app's weather code — NEVER a precise
 * latitude/longitude, address, or place name. §26: only the structured
 * facts actually used are stored here, never raw provider JSON.
 */
@Entity(tableName = "forecast_decision_receipts")
data class ForecastDecisionReceiptEntity(
    @PrimaryKey(autoGenerate = true) val rowSeq: Long = 0,
    val receiptId: String,
    val schemaVersion: Int,
    val appBuildId: String?,
    val policyVersion: Int,
    val thresholdConfigVersion: Int,
    val factsHash: String?,

    // --- §24 time / provenance ------------------------------------------
    val evaluatedAtUtcMs: Long,
    val requestedTargetDate: String,
    val providerTargetDate: String?,
    val forecastTimezone: String?,
    val fetchedAtMs: Long?,
    val forecastAgeMs: Long?,
    val providerRunTimestamp: String?,
    val providerId: String,
    val endpointKind: String,

    // --- §25 location (opaque, privacy-safe) ----------------------------
    val locationRevision: String?,
    val locationMode: String,
    val locationMatch: Boolean?,

    // --- §26 weather facts actually used ---------------------------------
    val rawWeatherCode: Int?,
    val category: String?,
    val precipitationSumMm: Double?,
    val rainSumMm: Double?,
    val showersSumMm: Double?,
    val snowfallSumCm: Double?,
    val precipitationProbabilityMaxPercent: Double?,
    val precipitationHours: Double?,
    val fieldsMissing: String,

    // --- §27 validation / decision --------------------------------------
    val dateMatch: Boolean,
    val freshnessResult: String,
    val requestStatus: String,
    val factsSource: String,
    val hazard: String?,
    val decisionReason: String,
    val candidateCreated: Boolean,
    val precipitationFallbackUsed: Boolean,
    val usedHourlyEvidenceCount: Int,

    // --- §27 delivery linkage --------------------------------------------
    val occurrenceKey: String?,
    val triggerSource: String?,
    val notificationNamespace: String?,
    val dispatchAttemptId: String?,
    val outcomeEventsJson: String = "[]",
)

@Dao
interface ForecastDecisionReceiptDao {

    @Insert
    suspend fun insert(entity: ForecastDecisionReceiptEntity): Long

    @Query("SELECT * FROM forecast_decision_receipts WHERE rowSeq = :rowSeq")
    suspend fun find(rowSeq: Long): ForecastDecisionReceiptEntity?

    @Query("SELECT * FROM forecast_decision_receipts WHERE receiptId = :receiptId")
    suspend fun findByReceiptId(receiptId: String): ForecastDecisionReceiptEntity?

    @Query("UPDATE forecast_decision_receipts SET outcomeEventsJson = :outcomeEventsJson WHERE rowSeq = :rowSeq")
    suspend fun updateOutcomeEvents(rowSeq: Long, outcomeEventsJson: String): Int

    @Query("SELECT * FROM forecast_decision_receipts ORDER BY rowSeq DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ForecastDecisionReceiptEntity>

    @Query("SELECT COUNT(*) FROM forecast_decision_receipts")
    suspend fun count(): Int

    /** §28 — bounded local retention: 90 days AND max 4096 detailed rows. */
    @Query("DELETE FROM forecast_decision_receipts WHERE evaluatedAtUtcMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query(
        "DELETE FROM forecast_decision_receipts WHERE rowSeq IN (" +
            "SELECT rowSeq FROM forecast_decision_receipts ORDER BY rowSeq ASC LIMIT :excess" +
            ")",
    )
    suspend fun deleteOldestExcess(excess: Int): Int
}
