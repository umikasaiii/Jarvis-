package com.simone.jarvismobile.responsibility

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1A. Adds the two durable
 * Responsibility-kernel tables — non-destructive, like every migration in
 * this project: fresh tables, no existing data touched.
 *
 * § MICRO-PATCH E.1 lesson, applied deliberately here: the raw SQL below
 * MUST create exactly the columns and indices that
 * [ResponsibilityEntity]/[ResponsibilityJournalRowEntity] declare via their
 * `@Entity`/`@Index` annotations — nothing more, nothing less. A mismatch
 * between what a migration creates and what the entity annotation expects
 * throws `IllegalStateException` on Room's schema validation on the first
 * open after an in-place upgrade (never on a clean install, which is
 * exactly why that specific device bug went unnoticed for as long as it
 * did — see `TriggerEvidenceRowEntity`'s doc comment for the full post-
 * mortem). `agent_responsibilities` declares no secondary indices (both its
 * queries go by the `@PrimaryKey` itself), so this migration creates none
 * beyond the table; `agent_responsibility_journal` declares `key`/`atMs`
 * indices, created explicitly below to match.
 */
object ResponsibilityMigrations {
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_responsibilities` (
                    `logicalKey` TEXT NOT NULL PRIMARY KEY,
                    `type` TEXT NOT NULL,
                    `lifecycleState` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `createdAtMs` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    `priority` INTEGER NOT NULL DEFAULT 0,
                    `deadlineAtMs` INTEGER,
                    `recheckAtMs` INTEGER,
                    `lastDecision` TEXT,
                    `lastVerificationOutcome` TEXT,
                    `linkedActionOccurrenceKey` TEXT,
                    `terminalReason` TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_responsibility_journal` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `atMs` INTEGER NOT NULL,
                    `key` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `fromState` TEXT,
                    `toState` TEXT NOT NULL,
                    `decision` TEXT,
                    `verificationOutcome` TEXT,
                    `reasonCode` TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_responsibility_journal_key` ON `agent_responsibility_journal` (`key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_responsibility_journal_atMs` ON `agent_responsibility_journal` (`atMs`)")
        }
    }

    /** Every migration that must be registered on the database builder. */
    val ALL = arrayOf(MIGRATION_14_15)
}
