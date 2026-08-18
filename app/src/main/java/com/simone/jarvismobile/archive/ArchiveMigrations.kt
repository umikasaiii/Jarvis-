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

    /**
     * Adds `archive_lists`/`archive_list_items` (generic Shopping/custom lists,
     * spec §4) and `archive_links` (cross-references between archive entities,
     * spec §13). Same non-destructive rule as [MIGRATION_4_5]: a shopping list
     * or a "Ricambi moto" list is user data, never dropped on a schema bump.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `archive_lists` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `archive_list_items` (
                    `id` TEXT NOT NULL,
                    `listId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `quantity` INTEGER,
                    `priority` TEXT NOT NULL,
                    `dueDate` INTEGER,
                    `notes` TEXT NOT NULL,
                    `link` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `order` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_list_items_listId` ON `archive_list_items` (`listId`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `archive_links` (
                    `id` TEXT NOT NULL,
                    `fromType` TEXT NOT NULL,
                    `fromId` TEXT NOT NULL,
                    `toType` TEXT NOT NULL,
                    `toId` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_archive_links_fromType_fromId` ON `archive_links` (`fromType`, `fromId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_archive_links_toType_toId` ON `archive_links` (`toType`, `toId`)",
            )
        }
    }

    val ALL = arrayOf(MIGRATION_4_5, MIGRATION_5_6)
}
