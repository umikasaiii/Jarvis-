package com.simone.jarvismobile.archive

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.archive.ArchiveStatus
import kotlinx.coroutines.flow.Flow

/**
 * Room persistence for the personal archive (NOTE / TO_WATCH — see
 * [ArchiveItem]'s own doc comment for why TODO and MEMORY are not here too).
 * Enums as plain strings, same convention as [com.simone.jarvismobile.document.DocumentEntity].
 */
@Entity(tableName = "archive_items", indices = [Index("kind"), Index("folder")])
data class ArchiveItemEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val content: String,
    val tags: String,
    val watchType: String,
    val status: String,
    val link: String,
    val folder: String,
    val pinned: Boolean,
    val theme: String,
    val spacing: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface ArchiveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ArchiveItemEntity)

    @Query("SELECT * FROM archive_items ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ArchiveItemEntity>>

    @Query("SELECT * FROM archive_items ORDER BY updatedAt DESC")
    suspend fun all(): List<ArchiveItemEntity>

    @Query("SELECT * FROM archive_items WHERE kind = :kind ORDER BY updatedAt DESC")
    suspend fun byKind(kind: String): List<ArchiveItemEntity>

    @Query("SELECT * FROM archive_items WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ArchiveItemEntity?

    @Query("DELETE FROM archive_items WHERE id = :id")
    suspend fun delete(id: String)
}

private const val TAG_SEPARATOR = ""

fun ArchiveItemEntity.toModel(): ArchiveItem = ArchiveItem(
    id = id,
    kind = runCatching { ArchiveKind.valueOf(kind) }.getOrDefault(ArchiveKind.NOTE),
    title = title,
    content = content,
    tags = tags.split(TAG_SEPARATOR).filter { it.isNotBlank() },
    watchType = watchType,
    status = runCatching { ArchiveStatus.valueOf(status) }.getOrDefault(ArchiveStatus.OPEN),
    link = link,
    folder = folder,
    pinned = pinned,
    theme = theme,
    spacing = spacing,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ArchiveItem.toEntity(): ArchiveItemEntity = ArchiveItemEntity(
    id = id,
    kind = kind.name,
    title = title,
    content = content,
    tags = tags.joinToString(TAG_SEPARATOR),
    watchType = watchType,
    status = status.name,
    link = link,
    folder = folder,
    pinned = pinned,
    theme = theme,
    spacing = spacing,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
