package com.simone.jarvismobile.engine.memory

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `conversational_memory` table (Conversational AI engine's Episodic
 * memory tier). A real migration, not the database's
 * `fallbackToDestructiveMigration()`, for the same reason
 * [com.simone.jarvismobile.archive.ArchiveMigrations] exists: a pending-task
 * snapshot or a recalled episode the engine has already built up is exactly as
 * irreplaceable as a note or an automation rule.
 */
object EngineMemoryMigrations {

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversational_memory` (
                    `id` TEXT NOT NULL,
                    `tier` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `lastAccessed` INTEGER NOT NULL,
                    `importance` REAL NOT NULL,
                    `source` TEXT NOT NULL,
                    `sessionId` TEXT,
                    `metadata` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversational_memory_tier` ON `conversational_memory` (`tier`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversational_memory_lastAccessed` ON `conversational_memory` (`lastAccessed`)",
            )
        }
    }

    val ALL = arrayOf(MIGRATION_6_7)
}
