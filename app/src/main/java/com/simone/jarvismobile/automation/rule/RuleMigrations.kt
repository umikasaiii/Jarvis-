package com.simone.jarvismobile.automation.rule

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for the automation tables.
 *
 * **Why these are hand-written and the other tables' are not.** The rest of
 * `jarvis.db` holds rebuildable caches — documents can be re-imported, the
 * navigation index can be regenerated — so the database is configured with
 * `fallbackToDestructiveMigration()`, and losing those tables on an upgrade
 * costs nothing but time. Automation rules and places are the opposite: the user
 * typed them, and nothing anywhere can rebuild them. A destructive upgrade would
 * silently delete work that only exists here.
 *
 * So every version bump from 3 onwards MUST ship a real migration covering these
 * tables. The destructive fallback stays in place for the older cache-only
 * versions, but it must never be what handles an upgrade that carries
 * automations.
 */
object RuleMigrations {

    /** Adds the automation engine's tables. Version 3 had none of them. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `automation_rules` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `triggersJson` TEXT NOT NULL,
                    `conditionJson` TEXT,
                    `actionsJson` TEXT NOT NULL,
                    `priority` INTEGER NOT NULL,
                    `executionPolicy` TEXT NOT NULL,
                    `cooldownSeconds` INTEGER NOT NULL,
                    `oneShot` INTEGER NOT NULL,
                    `expiresAt` TEXT,
                    `lastFiredAt` TEXT,
                    `createdAt` TEXT,
                    `updatedAt` TEXT,
                    `schemaVersion` INTEGER NOT NULL,
                    `quarantined` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_automation_rules_enabled` ON `automation_rules` (`enabled`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `automation_places` (
                    `id` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `latitude` REAL NOT NULL,
                    `longitude` REAL NOT NULL,
                    `radiusMeters` REAL NOT NULL,
                    `type` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `expiresAt` TEXT,
                    `createdAt` TEXT,
                    `updatedAt` TEXT,
                    `schemaVersion` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `automation_executions` (
                    `executionId` TEXT NOT NULL,
                    `ruleId` TEXT NOT NULL,
                    `ruleName` TEXT NOT NULL,
                    `triggerType` TEXT NOT NULL,
                    `startedAt` TEXT NOT NULL,
                    `finishedAt` TEXT,
                    `decision` TEXT NOT NULL,
                    `reason` TEXT NOT NULL,
                    `actionOutcomes` TEXT NOT NULL,
                    `dryRun` INTEGER NOT NULL,
                    PRIMARY KEY(`executionId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_automation_executions_ruleId` ON `automation_executions` (`ruleId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_automation_executions_startedAt` " +
                    "ON `automation_executions` (`startedAt`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `parking_location` (
                    `id` INTEGER NOT NULL,
                    `latitude` REAL NOT NULL,
                    `longitude` REAL NOT NULL,
                    `accuracyMeters` REAL,
                    `savedAt` TEXT NOT NULL,
                    `label` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }

    /** Every migration that must be registered on the database builder. */
    val ALL = arrayOf(MIGRATION_3_4)
}
