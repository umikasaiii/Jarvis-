package com.simone.jarvismobile.background

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.simone.jarvismobile.archive.ArchiveMigrations
import com.simone.jarvismobile.automation.rule.RuleMigrations
import com.simone.jarvismobile.engine.memory.EngineMemoryMigrations
import com.simone.jarvismobile.proactive.ProactiveOccurrenceMigrations
import com.simone.jarvismobile.proactive.ProactiveSchedulePlanMigrations
import com.simone.jarvismobile.proactive.TriggerEvidenceMigrations
import com.simone.jarvismobile.responsibility.ResponsibilityMigrations

/**
 * STEP 2 — the single registry of every `jarvis.db` migration.
 *
 * `DatabaseModule` no longer has `fallbackToDestructiveMigration()`: a version
 * the registry cannot take to [CURRENT_VERSION] makes Room (and
 * [DatabaseOpenGuard]) refuse to open the file instead of recreating it empty.
 * [missingSteps] is the machine-checkable statement that the chain is
 * contiguous; `JarvisMigrationsTest` fails the build if a `version` bump ships
 * without its migration.
 *
 * **When you bump `JarvisDatabase.version`:** add the `MIGRATION_n_n+1`, add it
 * to [ALL], update [CURRENT_VERSION], commit the exported schema
 * (`app/schemas/.../<n+1>.json`) and add a seeded test case to
 * `JarvisMigrationInstrumentedTest`.
 */
object JarvisMigrations {

    /** Must equal `@Database(version = …)` on [JarvisDatabase] (pinned by a unit test). */
    const val CURRENT_VERSION = 15

    /** The first schema version ever shipped (6d, persistent response queue). */
    const val OLDEST_SUPPORTED_VERSION = 1

    /**
     * Version 1 → 2: adds the document-import tables (a52720f). Purely additive; the
     * DDL mirrors `DocumentEntity` / `DocumentChunkEntity`, unchanged since then.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `documents` (
                    `id` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `originalUri` TEXT NOT NULL,
                    `vaultPath` TEXT,
                    `mimeType` TEXT NOT NULL,
                    `fileSize` INTEGER NOT NULL,
                    `checksumSha256` TEXT NOT NULL,
                    `importedAt` INTEGER NOT NULL,
                    `modifiedAt` INTEGER NOT NULL,
                    `parserVersion` INTEGER NOT NULL,
                    `indexVersion` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `pageCount` INTEGER,
                    `language` TEXT,
                    `title` TEXT,
                    `author` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `document_chunks` (
                    `id` TEXT NOT NULL,
                    `documentId` TEXT NOT NULL,
                    `text` TEXT NOT NULL,
                    `section` TEXT NOT NULL,
                    `page` INTEGER,
                    `chunkIndex` INTEGER NOT NULL,
                    `tokenEstimate` INTEGER NOT NULL,
                    `documentTitle` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_chunks_documentId` ON `document_chunks` (`documentId`)")
        }
    }

    /**
     * Version 2 → 3: adds the offline-navigation tables (6054522): the FTS4 place index,
     * favourites and history. Purely additive; DDL mirrors `NavEntities.kt`.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `nav_places` USING FTS4(" +
                    "`refId` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, " +
                    "`category` TEXT NOT NULL, `regionId` TEXT NOT NULL, `lat` REAL NOT NULL, " +
                    "`lon` REAL NOT NULL, `importance` REAL NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `nav_favorites` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`label` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `placeId` TEXT, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `nav_history` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                    "`lat` REAL NOT NULL, `lon` REAL NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
        }
    }

    /** Every migration, 1 → 15. Order is irrelevant to Room; [missingSteps] checks contiguity. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3,
        *RuleMigrations.ALL,            // 3→4, 9→10, 10→11
        *ArchiveMigrations.ALL,         // 4→5, 5→6, 7→8, 8→9
        *EngineMemoryMigrations.ALL,    // 6→7
        *ProactiveOccurrenceMigrations.ALL,   // 11→12
        *TriggerEvidenceMigrations.ALL,       // 12→13
        *ProactiveSchedulePlanMigrations.ALL, // 13→14
        *ResponsibilityMigrations.ALL,        // 14→15
    )

    /** Versions `n` for which the single step `n → n+1` is not registered. Empty = contiguous chain. */
    fun missingSteps(
        migrations: Array<Migration> = ALL,
        from: Int = OLDEST_SUPPORTED_VERSION,
        to: Int = CURRENT_VERSION,
    ): List<Int> = (from until to).filter { n ->
        migrations.none { it.startVersion == n && it.endVersion == n + 1 }
    }
}
