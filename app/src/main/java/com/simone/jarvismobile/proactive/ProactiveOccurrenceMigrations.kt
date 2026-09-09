package com.simone.jarvismobile.proactive

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.1 §F. Adds the durable
 * occurrence-claim table for Morning Brief delivery — non-destructive, like
 * every migration in this project: a fresh table, no existing data touched.
 */
object ProactiveOccurrenceMigrations {
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `proactive_occurrences` (
                    `occurrenceKey` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `logicalDate` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `triggerSource` TEXT NOT NULL,
                    `claimedAtMs` INTEGER NOT NULL,
                    `generatedAtMs` INTEGER,
                    `deliveryAttemptAtMs` INTEGER,
                    `deliveredAtMs` INTEGER,
                    `retryCount` INTEGER NOT NULL DEFAULT 0,
                    `terminalReason` TEXT,
                    PRIMARY KEY(`occurrenceKey`)
                )
                """.trimIndent(),
            )
        }
    }

    /** Every migration that must be registered on the database builder. */
    val ALL = arrayOf(MIGRATION_11_12)
}
