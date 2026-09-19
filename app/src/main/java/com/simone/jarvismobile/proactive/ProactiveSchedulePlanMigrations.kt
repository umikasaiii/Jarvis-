package com.simone.jarvismobile.proactive

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §6. Non-destructive: a fresh table, no existing data
 * touched — same pattern as every other migration in this project. Baseline
 * confirmed at version 13 before this change (Work Package A made no schema
 * increment — new occurrence states were just new TEXT-column string
 * values), so this is the next real one: 13 -> 14.
 */
object ProactiveSchedulePlanMigrations {
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `proactive_schedule_plans` (
                    `source` TEXT NOT NULL,
                    `planRevision` INTEGER NOT NULL,
                    `logicalDate` TEXT NOT NULL,
                    `intendedFireAtMs` INTEGER NOT NULL,
                    `sourceAlarmAtMs` INTEGER,
                    `settingsSnapshotHour` INTEGER,
                    `settingsSnapshotMinute` INTEGER,
                    `settingsSnapshotOffsetMinutes` INTEGER,
                    `enabled` INTEGER NOT NULL,
                    `reconciliationOutcome` TEXT NOT NULL,
                    `exactness` TEXT NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`source`)
                )
                """.trimIndent(),
            )
        }
    }

    /** Every migration that must be registered on the database builder. */
    val ALL = arrayOf(MIGRATION_13_14)
}
