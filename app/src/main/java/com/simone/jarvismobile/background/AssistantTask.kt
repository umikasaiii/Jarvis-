package com.simone.jarvismobile.background

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AssistantTaskStatus {
    QUEUED,
    LOADING_MODEL,
    UNDERSTANDING,
    RETRIEVING_MEMORY,
    GENERATING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Entity(tableName = "assistant_tasks")
data class AssistantTask(
    @PrimaryKey val id: String,
    val input: String,
    val status: String = AssistantTaskStatus.QUEUED.name,
    val progress: Int = 0,
    val output: String? = null,
    val errorCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val parsedStatus: AssistantTaskStatus
        get() = runCatching { AssistantTaskStatus.valueOf(status) }
            .getOrDefault(AssistantTaskStatus.FAILED)
}
