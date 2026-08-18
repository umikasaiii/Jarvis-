package com.simone.jarvismobile.archive

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.simone.jarvismobile.core.archive.ArchiveLink
import com.simone.jarvismobile.core.archive.ArchiveList
import com.simone.jarvismobile.core.archive.ArchiveListItem
import com.simone.jarvismobile.core.archive.ArchiveListType
import com.simone.jarvismobile.core.archive.ArchiveRef
import com.simone.jarvismobile.core.archive.ListItemStatus
import kotlinx.coroutines.flow.Flow

/** Room persistence for [ArchiveList]/[ArchiveListItem]/[ArchiveLink] (spec §4/§13). */
@Entity(tableName = "archive_lists")
data class ArchiveListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "archive_list_items", indices = [Index("listId")])
data class ArchiveListItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val title: String,
    val description: String,
    val status: String,
    val quantity: Int?,
    val priority: String,
    val dueDate: Long?,
    val notes: String,
    val link: String,
    val tags: String,
    val order: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "archive_links", indices = [Index("fromType", "fromId"), Index("toType", "toId")])
data class ArchiveLinkEntity(
    @PrimaryKey val id: String,
    val fromType: String,
    val fromId: String,
    val toType: String,
    val toId: String,
    val createdAt: Long,
)

@Dao
interface ArchiveListDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertList(list: ArchiveListEntity)

    @Query("SELECT * FROM archive_lists ORDER BY name")
    fun observeLists(): Flow<List<ArchiveListEntity>>

    @Query("SELECT * FROM archive_lists ORDER BY name")
    suspend fun allLists(): List<ArchiveListEntity>

    @Query("SELECT * FROM archive_lists WHERE id = :id LIMIT 1")
    suspend fun listById(id: String): ArchiveListEntity?

    @Query("SELECT * FROM archive_lists WHERE type = :type LIMIT 1")
    suspend fun listByType(type: String): ArchiveListEntity?

    @Query("DELETE FROM archive_lists WHERE id = :id")
    suspend fun deleteList(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: ArchiveListItemEntity)

    @Query("SELECT * FROM archive_list_items ORDER BY `order`, updatedAt DESC")
    fun observeAllItems(): Flow<List<ArchiveListItemEntity>>

    @Query("SELECT * FROM archive_list_items ORDER BY `order`, updatedAt DESC")
    suspend fun allItems(): List<ArchiveListItemEntity>

    @Query("SELECT * FROM archive_list_items WHERE listId = :listId ORDER BY `order`, updatedAt DESC")
    suspend fun itemsForList(listId: String): List<ArchiveListItemEntity>

    @Query("SELECT * FROM archive_list_items WHERE id = :id LIMIT 1")
    suspend fun itemById(id: String): ArchiveListItemEntity?

    @Query("DELETE FROM archive_list_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("DELETE FROM archive_list_items WHERE listId = :listId")
    suspend fun deleteItemsOfList(listId: String)
}

@Dao
interface ArchiveLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: ArchiveLinkEntity)

    @Query("SELECT * FROM archive_links WHERE fromType = :type AND fromId = :id OR toType = :type AND toId = :id")
    suspend fun linksFor(type: String, id: String): List<ArchiveLinkEntity>

    @Query("DELETE FROM archive_links WHERE id = :id")
    suspend fun delete(id: String)
}

private const val TAG_SEPARATOR = ""

fun ArchiveListEntity.toModel(): ArchiveList = ArchiveList(
    id = id,
    name = name,
    type = runCatching { ArchiveListType.valueOf(type) }.getOrDefault(ArchiveListType.CUSTOM),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ArchiveList.toEntity(): ArchiveListEntity = ArchiveListEntity(
    id = id,
    name = name,
    type = type.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ArchiveListItemEntity.toModel(): ArchiveListItem = ArchiveListItem(
    id = id,
    listId = listId,
    title = title,
    description = description,
    status = runCatching { ListItemStatus.valueOf(status) }.getOrDefault(ListItemStatus.OPEN),
    quantity = quantity,
    priority = priority,
    dueDate = dueDate,
    notes = notes,
    link = link,
    tags = tags.split(TAG_SEPARATOR).filter { it.isNotBlank() },
    order = order,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ArchiveListItem.toEntity(): ArchiveListItemEntity = ArchiveListItemEntity(
    id = id,
    listId = listId,
    title = title,
    description = description,
    status = status.name,
    quantity = quantity,
    priority = priority,
    dueDate = dueDate,
    notes = notes,
    link = link,
    tags = tags.joinToString(TAG_SEPARATOR),
    order = order,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ArchiveLinkEntity.toModel(): ArchiveLink = ArchiveLink(
    id = id,
    from = ArchiveRef(fromType, fromId),
    to = ArchiveRef(toType, toId),
    createdAt = createdAt,
)

fun ArchiveLink.toEntity(): ArchiveLinkEntity = ArchiveLinkEntity(
    id = id,
    fromType = from.type,
    fromId = from.id,
    toType = to.type,
    toId = to.id,
    createdAt = createdAt,
)
