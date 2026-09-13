package com.simone.jarvismobile.proactive

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.3 §4/§5. Adds the
 * persistent trigger-evidence table — non-destructive, like every migration
 * in this project: a fresh table, no existing data touched.
 */
object TriggerEvidenceMigrations {
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `trigger_evidence` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `eventAtMs` INTEGER NOT NULL,
                    `processSessionId` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `stage` TEXT NOT NULL,
                    `detail` TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_evidence_source` ON `trigger_evidence` (`source`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_evidence_eventAtMs` ON `trigger_evidence` (`eventAtMs`)")
        }
    }

    /** Every migration that must be registered on the database builder. */
    val ALL = arrayOf(MIGRATION_12_13)
}
