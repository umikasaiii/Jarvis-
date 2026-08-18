package com.simone.jarvismobile.archive

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `archive_items` table (Modalità Pro's personal notes/to-watch list).
 * A real migration, not the database's `fallbackToDestructiveMigration()`,
 * for the same reason [com.simone.jarvismobile.automation.rule.RuleMigrations]
 * exists: a note or a to-watch item the user typed is exactly as irreplaceable
 * as an automation rule, and a destructive fallback would silently delete it
 * on the next schema bump.
 */
object ArchiveMigrations {

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `archive_items` (
                    `id` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `watchType` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `link` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_items_kind` ON `archive_items` (`kind`)")
        }
    }

    val ALL = arrayOf(MIGRATION_4_5)
}
