package com.simone.jarvismobile.background

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AssistantTaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: AssistantTask): Long

    @Query("SELECT * FROM assistant_tasks WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AssistantTask?

    @Query("SELECT * FROM assistant_tasks ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<AssistantTask>>

    @Query(
        "SELECT COUNT(*) FROM assistant_tasks " +
            "WHERE status IN ('QUEUED','LOADING_MODEL','UNDERSTANDING','RETRIEVING_MEMORY','GENERATING')",
    )
    fun observeActiveCount(): Flow<Int>

    @Query(
        "SELECT id FROM assistant_tasks " +
            "WHERE status IN ('QUEUED','LOADING_MODEL','UNDERSTANDING','RETRIEVING_MEMORY','GENERATING') " +
            "ORDER BY createdAt DESC",
    )
    suspend fun activeIds(): List<String>

    /** HOTFIX 4 — active ids created up to [cutoff]: a Stop must not cancel a message sent right after it. */
    @Query(
        "SELECT id FROM assistant_tasks WHERE createdAt <= :cutoff " +
            "AND status IN ('QUEUED','LOADING_MODEL','UNDERSTANDING','RETRIEVING_MEMORY','GENERATING') " +
            "ORDER BY createdAt DESC",
    )
    suspend fun activeIdsUpTo(cutoff: Long): List<String>

    /** HOTFIX 4 — completes a task unless the user already cancelled it (no late "answer ready" for a Stop). */
    @Query(
        "UPDATE assistant_tasks SET status = 'COMPLETED', progress = 100, output = :output, errorCode = NULL, " +
            "updatedAt = :updatedAt WHERE id = :id AND status != 'CANCELLED'",
    )
    suspend fun completeUnlessCancelled(id: String, output: String, updatedAt: Long = System.currentTimeMillis()): Int

    /** HOTFIX 4 — already-ANSWERED tasks newer than this one: a retry of [id] now would surface out of order. */
    @Query("SELECT COUNT(*) FROM assistant_tasks WHERE id != :id AND createdAt > :createdAt AND status = 'COMPLETED'")
    suspend fun newerCompletedCount(id: String, createdAt: Long): Int

    /** BETA 2/8 — (id, input) of tasks still queued or running; read-only query, no schema change. */
    @Query(
        "SELECT id, input FROM assistant_tasks " +
            "WHERE status IN ('QUEUED','LOADING_MODEL','UNDERSTANDING','RETRIEVING_MEMORY','GENERATING') " +
            "ORDER BY createdAt",
    )
    suspend fun activeTasks(): List<ActiveTaskRow>

    /** BETA 2/8 — how many OLDER tasks are still active: a newer answer waits its turn so the chat keeps arrival order. */
    @Query(
        "SELECT COUNT(*) FROM assistant_tasks WHERE id != :id AND createdAt < :createdAt " +
            "AND updatedAt >= :staleBefore " +
            "AND status IN ('QUEUED','LOADING_MODEL','UNDERSTANDING','RETRIEVING_MEMORY','GENERATING')",
    )
    suspend fun olderActiveCount(id: String, createdAt: Long, staleBefore: Long): Int

    @Query(
        "UPDATE assistant_tasks SET status = :status, progress = :progress, " +
            "output = :output, errorCode = :errorCode, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: String,
        status: String,
        progress: Int,
        output: String? = null,
        errorCode: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM assistant_tasks WHERE createdAt < :before AND status IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun deleteFinishedBefore(before: Long)
}

/** Query projection (not an entity): the columns the duplicate guard needs. */
data class ActiveTaskRow(val id: String, val input: String)
