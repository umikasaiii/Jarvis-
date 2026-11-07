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
            ensureIndices(db)
        }
    }

    /**
     * The two indices [TriggerEvidenceRowEntity] declares. Idempotent.
     *
     * STEP 2 (found by running the migrations against the authentic exported schemas of
     * versions 13 and 14): a database created by a CLEAN INSTALL of a v13/v14 build from before
     * the E.1 fix has `trigger_evidence` WITHOUT these indices (the entity then declared none and
     * no migration ran), while the current entity expects them — Room's schema validation then
     * fails at open. Databases that reached v13 through [MIGRATION_12_13] already have them.
     * Migrations 13→14 and 14→15 therefore call this too, so every path ends on the same shape.
     */
    fun ensureIndices(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_evidence_source` ON `trigger_evidence` (`source`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_evidence_eventAtMs` ON `trigger_evidence` (`eventAtMs`)")
    }

    /** Every migration that must be registered on the database builder. */
    val ALL = arrayOf(MIGRATION_12_13)
}
