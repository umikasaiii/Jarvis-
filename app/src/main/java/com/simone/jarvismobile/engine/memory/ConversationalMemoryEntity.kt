package com.simone.jarvismobile.engine.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.simone.jarvismobile.core.memory.MemoryEntry
import com.simone.jarvismobile.core.memory.MemoryTier

/**
 * Room persistence for the Episodic memory tier (`ConversationalJarvisEngine`'s
 * `MemoryEngine`/`ConversationManager` — cross-session but non-permanent state,
 * e.g. a `ConversationManager.PendingTask` snapshot). Working memory stays on
 * the existing `ConversationMemoryStore`; Semantic/User memory stay on the
 * existing vault-backed `MemoryRecord` store — this table exists only for the
 * tier that genuinely had nowhere to live before. See
 * `docs/CONVERSATIONAL_ENGINE.md` for the full tier mapping.
 *
 * `metadata` is a JSON-string column, an empty object today — the reserved
 * seam for future entity-relation-entity triples ([MemoryEntry.entityTriples]),
 * never written to by anything yet.
 */
@Entity(
    tableName = "conversational_memory",
    indices = [Index("tier"), Index("lastAccessed")],
)
data class ConversationalMemoryEntity(
    @PrimaryKey val id: String,
    val tier: String,
    val content: String,
    val type: String,
    val createdAt: Long,
    val lastAccessed: Long,
    val importance: Double,
    val source: String,
    val sessionId: String?,
    val metadata: String,
)

@Dao
interface ConversationalMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationalMemoryEntity)

    @Query("SELECT * FROM conversational_memory WHERE tier = :tier ORDER BY lastAccessed DESC")
    suspend fun byTier(tier: String): List<ConversationalMemoryEntity>

    @Query("SELECT * FROM conversational_memory ORDER BY lastAccessed DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ConversationalMemoryEntity>

    @Query("SELECT * FROM conversational_memory WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ConversationalMemoryEntity?

    @Query("DELETE FROM conversational_memory WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversational_memory")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM conversational_memory")
    suspend fun count(): Int
}

fun ConversationalMemoryEntity.toModel(): MemoryEntry = MemoryEntry(
    id = id,
    content = content,
    type = type,
    tier = runCatching { MemoryTier.valueOf(tier) }.getOrDefault(MemoryTier.EPISODIC),
    timestamp = createdAt,
    importance = importance,
    source = source,
    lastAccessed = lastAccessed,
)

fun MemoryEntry.toEntity(sessionId: String? = null): ConversationalMemoryEntity = ConversationalMemoryEntity(
    id = id,
    tier = tier.name,
    content = content,
    type = type,
    createdAt = timestamp,
    lastAccessed = lastAccessed,
    importance = importance,
    source = source,
    sessionId = sessionId,
    metadata = "{}",
)
