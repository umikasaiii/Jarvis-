package com.simone.jarvismobile.proactive

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.1 §F. One row per
 * logical Morning Brief occurrence (`occurrenceKey`, PRIMARY KEY — see
 * [com.simone.jarvismobile.core.proactive.ProactiveOccurrenceKey]). The
 * ROW ITSELF, not any in-memory flag, is what "already claimed"/"already
 * delivered" durably means — survives process death, reboot, and app
 * restart (§F's own requirement). Never holds the briefing text itself
 * (§U/§O: bounded metadata only).
 */
@Entity(tableName = "proactive_occurrences")
data class ProactiveOccurrenceEntity(
    @PrimaryKey val occurrenceKey: String,
    val kind: String,
    val logicalDate: String,
    val state: String,
    val triggerSource: String,
    val claimedAtMs: Long,
    val generatedAtMs: Long? = null,
    val deliveryAttemptAtMs: Long? = null,
    val deliveredAtMs: Long? = null,
    val retryCount: Int = 0,
    val terminalReason: String? = null,
)

@Dao
interface ProactiveOccurrenceDao {

    @Query("SELECT * FROM proactive_occurrences WHERE occurrenceKey = :key")
    suspend fun find(key: String): ProactiveOccurrenceEntity?

    /**
     * § §G — the CROSS-PROCESS atomic claim for a BRAND NEW occurrence: a
     * unique-primary-key INSERT that SQLite itself serializes. Returns -1L
     * (Room's [OnConflictStrategy.IGNORE] convention) when a row with this
     * key already exists — i.e. someone else won the race — never throws,
     * never silently overwrites.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun tryInsertClaim(entity: ProactiveOccurrenceEntity): Long

    /**
     * § §G/§M — the CROSS-PROCESS atomic TAKEOVER of an existing occurrence
     * whose prior attempt is stale or provably failed-without-effect. The
     * WHERE clause re-validates the takeover condition INSIDE the same
     * atomic UPDATE statement (never a separate read-then-write), so two
     * concurrent takeover attempts can only ever have one winner — the
     * loser's UPDATE simply matches zero rows. `retryCount` increments so
     * diagnostics can show how many times an occurrence needed reclaiming.
     */
    @Query(
        "UPDATE proactive_occurrences SET " +
            "state = :newState, triggerSource = :triggerSource, claimedAtMs = :nowMs, retryCount = retryCount + 1, " +
            "generatedAtMs = NULL, deliveryAttemptAtMs = NULL, deliveredAtMs = NULL, terminalReason = NULL " +
            "WHERE occurrenceKey = :key AND (" +
            "  state = 'FAILED_RETRYABLE' " +
            "  OR (state IN ('CLAIMED', 'GENERATED', 'DELIVERY_PENDING') AND claimedAtMs <= :staleCutoffMs)" +
            ")",
    )
    suspend fun tryTakeover(key: String, staleCutoffMs: Long, newState: String, triggerSource: String, nowMs: Long): Int

    @Query("UPDATE proactive_occurrences SET state = :state, generatedAtMs = :atMs WHERE occurrenceKey = :key")
    suspend fun markGenerated(key: String, state: String, atMs: Long)

    @Query("UPDATE proactive_occurrences SET state = :state, deliveryAttemptAtMs = :atMs WHERE occurrenceKey = :key")
    suspend fun markDeliveryAttempt(key: String, state: String, atMs: Long)

    @Query("UPDATE proactive_occurrences SET state = :state, deliveredAtMs = :atMs WHERE occurrenceKey = :key")
    suspend fun markDelivered(key: String, state: String, atMs: Long)

    @Query("UPDATE proactive_occurrences SET state = :state, terminalReason = :reason WHERE occurrenceKey = :key")
    suspend fun markFailed(key: String, state: String, reason: String?)

    /** Bounded retention, mirrors [com.simone.jarvismobile.automation.rule.ExecutionLogRepository.prune] — old rows carry no useful dedup value once their logical date is long past. */
    @Query("DELETE FROM proactive_occurrences WHERE claimedAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
