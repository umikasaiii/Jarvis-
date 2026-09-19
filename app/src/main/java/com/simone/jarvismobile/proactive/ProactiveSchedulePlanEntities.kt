package com.simone.jarvismobile.proactive

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §5/§6. One row per scheduling SOURCE ("NEXT_ALARM" or
 * "CONFIGURED_TIME" — [com.simone.jarvismobile.core.proactive.ProactiveTriggerSource]
 * `.name`), never a second settings authority
 * ([com.simone.jarvismobile.data.SettingsRepository] remains that) — this is
 * the DURABLE DERIVED execution plan a scheduling source reconciled to, so
 * a fired [com.simone.jarvismobile.alarms.AlarmReceiver] intent can validate
 * its own plan identity against the CURRENT plan (§15) instead of trusting
 * its own possibly-stale extras. Survives process death/reboot — the whole
 * point of §5/§7's "durable" requirement.
 */
@Entity(tableName = "proactive_schedule_plans")
data class ProactiveSchedulePlanEntity(
    @PrimaryKey val source: String,
    val planRevision: Long,
    val logicalDate: String,
    val intendedFireAtMs: Long,
    val sourceAlarmAtMs: Long?,
    val settingsSnapshotHour: Int?,
    val settingsSnapshotMinute: Int?,
    val settingsSnapshotOffsetMinutes: Int?,
    val enabled: Boolean,
    val reconciliationOutcome: String,
    val exactness: String,
    val updatedAtMs: Long,
)

@Dao
interface ProactiveSchedulePlanDao {

    @Query("SELECT * FROM proactive_schedule_plans WHERE source = :source")
    suspend fun find(source: String): ProactiveSchedulePlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProactiveSchedulePlanEntity)
}
