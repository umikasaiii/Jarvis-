package com.simone.jarvismobile.automation.rule

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room storage for the generic automation engine (§23).
 *
 * Timestamps are ISO-8601 strings rather than epoch millis: they survive a
 * timezone change without silently shifting, they are readable when inspecting
 * the database during a bug hunt, and they are exactly what [RuleCodec] already
 * parses. The tree-shaped parts of a rule (triggers, condition, actions) are
 * stored as versioned JSON produced by that codec; the scalar fields are real
 * columns so the engine can query "which rules are enabled" without decoding
 * every payload.
 *
 * These tables hold **user-created data**, unlike the document and navigation
 * caches that share this database. See the migration note in `JarvisDatabase`.
 */

@Entity(
    tableName = "automation_rules",
    indices = [Index("enabled")],
)
data class AutomationRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val triggersJson: String,
    val conditionJson: String?,
    val actionsJson: String,
    val priority: Int,
    val executionPolicy: String,
    val cooldownSeconds: Long,
    val oneShot: Boolean,
    val expiresAt: String?,
    val lastFiredAt: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val schemaVersion: Int,
    /**
     * Set when the payload could not be decoded. The row is kept — deleting a
     * rule the user wrote because *we* cannot read it would be the worst
     * possible outcome — but it is never armed, and the UI can offer to repair
     * or remove it (§25 "malformed automation").
     */
    val quarantined: Boolean = false,
)

/** A geofenced place (§5). Coordinates come from the user, never from source. */
@Entity(tableName = "automation_places")
data class AutomationPlaceEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val type: String,
    val enabled: Boolean,
    /** Temporary places expire and are cleaned up (§6). */
    val expiresAt: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val schemaVersion: Int,
)

/**
 * One run of one rule, kept so JARVIS can answer "perché l'hai fatto?" (§21).
 *
 * Deliberately holds no coordinates and no message bodies — only which rule,
 * which trigger, what was decided and how it ended. A log that quietly
 * accumulated locations would be a tracking history nobody asked for.
 */
@Entity(
    tableName = "automation_executions",
    indices = [Index("ruleId"), Index("startedAt")],
)
data class AutomationExecutionEntity(
    @PrimaryKey val executionId: String,
    val ruleId: String,
    val ruleName: String,
    val triggerType: String,
    val startedAt: String,
    val finishedAt: String?,
    /** FIRED, SKIPPED_CONDITIONS, SKIPPED_COOLDOWN, SKIPPED_CAPABILITY, FAILED… */
    val decision: String,
    /** Short human-readable reason, safe to show: no personal content. */
    val reason: String,
    /** Action types attempted and their outcome, e.g. "SPEAK:ok,RUN_TOOL:failed". */
    val actionOutcomes: String,
    val dryRun: Boolean = false,
)

/** Where the vehicle was left (§16). A single current value, not a history. */
@Entity(tableName = "parking_location")
data class ParkingLocationEntity(
    @PrimaryKey val id: Int = 1,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val savedAt: String,
    val label: String?,
)

@Dao
interface AutomationRuleDao {

    @Query("SELECT * FROM automation_rules ORDER BY priority DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rules ORDER BY priority DESC, name COLLATE NOCASE ASC")
    suspend fun all(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1 AND quarantined = 0")
    suspend fun enabled(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE id = :id")
    suspend fun byId(id: String): AutomationRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AutomationRuleEntity)

    @Update
    suspend fun update(rule: AutomationRuleEntity)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE automation_rules SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: String)

    @Query("UPDATE automation_rules SET lastFiredAt = :now WHERE id = :id")
    suspend fun markFired(id: String, now: String)

    @Query("SELECT COUNT(*) FROM automation_rules")
    suspend fun count(): Int
}

@Dao
interface AutomationPlaceDao {

    @Query("SELECT * FROM automation_places ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AutomationPlaceEntity>>

    @Query("SELECT * FROM automation_places WHERE enabled = 1")
    suspend fun enabled(): List<AutomationPlaceEntity>

    @Query("SELECT * FROM automation_places WHERE id = :id")
    suspend fun byId(id: String): AutomationPlaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: AutomationPlaceEntity)

    @Query("DELETE FROM automation_places WHERE id = :id")
    suspend fun delete(id: String)

    /** Removes temporary places whose moment has passed (§6). */
    @Query("DELETE FROM automation_places WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: String): Int
}

@Dao
interface AutomationExecutionDao {

    @Query("SELECT * FROM automation_executions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AutomationExecutionEntity>>

    @Query("SELECT * FROM automation_executions WHERE ruleId = :ruleId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun forRule(ruleId: String, limit: Int = 20): List<AutomationExecutionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(execution: AutomationExecutionEntity)

    /** Retention: the log is a diagnostic aid, not an archive (§21). */
    @Query("DELETE FROM automation_executions WHERE startedAt < :before")
    suspend fun deleteOlderThan(before: String): Int

    @Query("SELECT COUNT(*) FROM automation_executions")
    suspend fun count(): Int
}

@Dao
interface ParkingLocationDao {

    @Query("SELECT * FROM parking_location WHERE id = 1")
    fun observe(): Flow<ParkingLocationEntity?>

    @Query("SELECT * FROM parking_location WHERE id = 1")
    suspend fun current(): ParkingLocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(location: ParkingLocationEntity)

    @Query("DELETE FROM parking_location")
    suspend fun clear()
}
