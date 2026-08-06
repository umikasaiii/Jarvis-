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
