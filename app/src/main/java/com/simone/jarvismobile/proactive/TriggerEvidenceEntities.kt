package com.simone.jarvismobile.proactive

import androidx.room.Dao
import androidx.room.Entity
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
 */
@Entity(tableName = "trigger_evidence")
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
