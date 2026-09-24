package com.simone.jarvismobile.proactive

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.3 §4/§5. One row
 * per bounded trigger-diagnostic checkpoint (see
 * [com.simone.jarvismobile.core.proactive.TriggerEvidenceEntry]) — survives
 * process restart, which is exactly what the in-memory-only receipts of
 * MICRO-PATCH 14.2.2 could not do. Never holds briefing/agenda/health/
 * weather content; [detail] is already bounded by
 * [com.simone.jarvismobile.core.proactive.TriggerEvidencePolicy] before it
 * ever reaches this row.
 *
 * § MICRO-PATCH E.1 — PROVEN ROOT CAUSE of the device cold-start/upgrade
 * crash. `indices` MUST mirror exactly what
 * [TriggerEvidenceMigrations.MIGRATION_12_13] creates via raw
 * `CREATE INDEX` SQL (`source`, `eventAtMs`) — this annotation previously
 * declared none. Room's own schema validation on open (`onValidateSchema`)
 * compares the ENTITY-derived expected `TableInfo` (columns AND indices)
 * against the ACTUAL live schema after every migration runs; a mismatch
 * throws `IllegalStateException` on the very first open of the WHOLE shared
 * `JarvisDatabase` after migrating through v12→v13 — poisoning every
 * subsequent DAO call on that same Singleton instance for the rest of the
 * process's life, not just this table's. This only manifests on an
 * IN-PLACE UPGRADE from schema ≤12 (the real migration path runs and
 * creates the two indices this annotation now declares); a clean install
 * never migrates at all — Room just calls `createAllTables()` from the
 * CURRENT entity shape, which was self-consistent even before this fix —
 * exactly why "works after reinstall" masked this instead of proving
 * anything. Every other migration+entity pair in this codebase already
 * follows this same match (see `ArchiveItemEntity`, `AutomationRuleEntity`,
 * `AutomationExecutionEntity`, `ConversationalMemoryEntity`) — this was the
 * one outlier. See `TriggerEvidenceMigrationRegressionTest` (androidTest)
 * for the executable regression test.
 */
@Entity(
    tableName = "trigger_evidence",
    indices = [Index("source"), Index("eventAtMs")],
)
data class TriggerEvidenceRowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventAtMs: Long,
    val processSessionId: String,
    val source: String,
    val stage: String,
    val detail: String?,
)

@Dao
interface TriggerEvidenceDao {

    @Insert
    suspend fun insert(entity: TriggerEvidenceRowEntity): Long

    @Query("SELECT * FROM trigger_evidence WHERE source = :source ORDER BY id DESC LIMIT :limit")
    suspend fun recentBySource(source: String, limit: Int): List<TriggerEvidenceRowEntity>

    @Query("SELECT * FROM trigger_evidence ORDER BY id DESC LIMIT :limit")
    suspend fun recentAll(limit: Int): List<TriggerEvidenceRowEntity>

    /** Bounded retention per source — keeps only the newest [keep] rows for [source]. */
    @Query(
        "DELETE FROM trigger_evidence WHERE source = :source AND id NOT IN " +
            "(SELECT id FROM trigger_evidence WHERE source = :source ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun pruneSource(source: String, keep: Int)

    @Query("DELETE FROM trigger_evidence WHERE eventAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
