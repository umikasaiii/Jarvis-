package com.simone.jarvismobile.responsibility

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1A. One row per logical
 * Responsibility (see [com.simone.jarvismobile.core.responsibility.ResponsibilityKey]) —
 * the durable storage SHAPE of
 * [com.simone.jarvismobile.core.responsibility.ResponsibilityRecord], never a
 * second source of truth for what those fields mean (`:core` owns the
 * meaning; this entity only owns persistence). [revision] is the CAS
 * fencing token every mutation beyond the initial claim must present — see
 * [ResponsibilityDao.transitionIfRevisionMatches]. No `indices` are
 * declared here (`find`/the CAS update both query by the `@PrimaryKey`
 * itself, which SQLite already indexes implicitly) — see
 * [ResponsibilityMigrations.MIGRATION_14_15]'s doc comment for why this
 * must stay in exact sync with whatever this annotation declares, learned
 * the hard way from MICRO-PATCH E.1's `trigger_evidence` index mismatch.
 */
@Entity(tableName = "agent_responsibilities")
data class ResponsibilityEntity(
    @PrimaryKey val logicalKey: String,
    val type: String,
    val lifecycleState: String,
    val revision: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val priority: Int = 0,
    val deadlineAtMs: Long? = null,
    val recheckAtMs: Long? = null,
    val lastDecision: String? = null,
    val lastVerificationOutcome: String? = null,
    val linkedActionOccurrenceKey: String? = null,
    val terminalReason: String? = null,
)

@Dao
interface ResponsibilityDao {

    @Query("SELECT * FROM agent_responsibilities WHERE logicalKey = :key")
    suspend fun find(key: String): ResponsibilityEntity?

    /**
     * The atomic CREATE for a brand new logical Responsibility — a
     * unique-primary-key INSERT that SQLite itself serializes. Returns
     * `-1L` ([OnConflictStrategy.IGNORE]'s Room convention) when a row with
     * this key already exists, i.e. someone else created it first — never
     * throws, never silently overwrites. Mirrors
     * `com.simone.jarvismobile.proactive.ProactiveOccurrenceDao.tryInsertClaim`.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun tryInsert(entity: ResponsibilityEntity): Long

    /**
     * § PA-1A — explicit CAS/revision fencing: the WHERE clause re-validates
     * the caller's belief about the current [ResponsibilityEntity.revision]
     * INSIDE the same atomic UPDATE, so a stale writer (one whose view of
     * the record was superseded by a concurrent transition) writes zero
     * rows instead of clobbering a newer state — the returned affected-row
     * count is the caller's only honest signal of whether its write stuck.
     * Deliberately a single general-purpose transition query (never one
     * query per target state) since every field a transition can change is
     * already known at call time — mirrors the discipline of
     * `com.simone.jarvismobile.proactive.ProactiveOccurrenceDao`'s own
     * per-field `mark*` queries, generalized to one statement because this
     * table's mutable field set is uniform across every lifecycle edge.
     */
    @Query(
        "UPDATE agent_responsibilities SET " +
            "lifecycleState = :lifecycleState, revision = :newRevision, updatedAtMs = :updatedAtMs, " +
            "lastDecision = :lastDecision, lastVerificationOutcome = :lastVerificationOutcome, " +
            "recheckAtMs = :recheckAtMs, terminalReason = :terminalReason " +
            "WHERE logicalKey = :key AND revision = :expectedRevision",
    )
    suspend fun transitionIfRevisionMatches(
        key: String,
        expectedRevision: Long,
        newRevision: Long,
        lifecycleState: String,
        updatedAtMs: Long,
        lastDecision: String?,
        lastVerificationOutcome: String?,
        recheckAtMs: Long?,
        terminalReason: String?,
    ): Int

    /**
     * Bounded retention — deliberately scoped to TERMINAL rows only
     * ([com.simone.jarvismobile.core.responsibility.ResponsibilityLifecycleState.isTerminal]),
     * never an in-flight one: unlike a short-lived daily occurrence
     * (`ProactiveOccurrenceStore.pruneOld`), a Responsibility may legitimately
     * stay non-terminal for longer than any fixed age cutoff, so an
     * age-only prune would risk silently orphaning live work.
     */
    @Query(
        "DELETE FROM agent_responsibilities WHERE " +
            "lifecycleState IN ('COMPLETED', 'FAILED', 'EXPIRED') AND updatedAtMs < :cutoffMs",
    )
    suspend fun pruneTerminalOlderThan(cutoffMs: Long): Int
}

/**
 * § PA-1A §65.1 ("Journal / diagnostics"). One row per
 * [com.simone.jarvismobile.core.responsibility.ResponsibilityJournalEntry] —
 * bounded, privacy-safe (never briefing/agenda/health content; [reasonCode]
 * is already sanitized by
 * [com.simone.jarvismobile.core.responsibility.ResponsibilityJournalPolicy]
 * before it ever reaches this row — same discipline as
 * `com.simone.jarvismobile.proactive.TriggerEvidenceRowEntity.detail`).
 * `indices` MUST mirror exactly what
 * [ResponsibilityMigrations.MIGRATION_14_15] creates via raw `CREATE INDEX`
 * SQL (`key`, `atMs`) — see that migration's doc comment.
 */
@Entity(
    tableName = "agent_responsibility_journal",
    indices = [Index("key"), Index("atMs")],
)
data class ResponsibilityJournalRowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val key: String,
    val type: String,
    val fromState: String?,
    val toState: String,
    val decision: String?,
    val verificationOutcome: String?,
    val reasonCode: String?,
)

@Dao
interface ResponsibilityJournalDao {

    @Insert
    suspend fun insert(entity: ResponsibilityJournalRowEntity): Long

    @Query("SELECT * FROM agent_responsibility_journal WHERE key = :key ORDER BY id DESC LIMIT :limit")
    suspend fun recentForKey(key: String, limit: Int): List<ResponsibilityJournalRowEntity>

    /** Bounded retention per key — keeps only the newest [keep] rows for [key]. Mirrors `TriggerEvidenceDao.pruneSource`. */
    @Query(
        "DELETE FROM agent_responsibility_journal WHERE key = :key AND id NOT IN " +
            "(SELECT id FROM agent_responsibility_journal WHERE key = :key ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun pruneKey(key: String, keep: Int)

    @Query("DELETE FROM agent_responsibility_journal WHERE atMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
