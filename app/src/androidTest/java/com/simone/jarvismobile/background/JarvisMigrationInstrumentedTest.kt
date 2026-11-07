package com.simone.jarvismobile.background

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * STEP 2 — real `jarvis.db` upgrade tests (need a device/emulator: NOT run by CI, which only
 * compiles them; see docs/ANDROID_OFFLINE_STEP02_DATABASE_HARDENING.md).
 *
 * Three groups:
 *  1. **Schema-JSON driven** (`MigrationTestHelper`): for every exported schema version N that is
 *     present in `app/schemas/`, build a genuine version-N database, seed one row in every table,
 *     migrate to the current version with the full registry and check that (a) Room's own
 *     validation passes, (b) every pre-existing table, row and column value survived.
 *     Versions whose JSON is not committed yet are reported in the log and by
 *     [historicalSchemaCoverageIsReported]; they are NOT silently counted as passed.
 *  2. **Legacy chain from raw SQL** for versions 1 and 2, whose only tables (assistant_tasks,
 *     documents, document_chunks) are unchanged since then, so their DDL is exactly the entity DDL.
 *     The real [JarvisDatabase] is then opened and Room validates all current tables.
 *  3. **Guard behaviour**: a newer-than-supported file is refused without touching it, and an
 *     upgrade leaves a pre-migration snapshot.
 */
@RunWith(AndroidJUnit4::class)
class JarvisMigrationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dbName = "migration_test_jarvis.db"
    private val tag = "JarvisMigrationTest"

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, JarvisDatabase::class.java)

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
        File(context.getDatabasePath(dbName).parentFile, DatabaseOpenGuard.SNAPSHOT_DIR).deleteRecursively()
    }

    // ---------- 1. schema-JSON driven ----------

    private fun schemaVersions(): List<Int> =
        instrumentation.context.assets.list(JarvisDatabase::class.java.name).orEmpty()
            .mapNotNull { Regex("""(\d+)\.json""").matchEntire(it)?.groupValues?.get(1)?.toInt() }
            .sorted()

    @Test fun currentSchemaIsExportedAndOpens() {
        val cur = JarvisMigrations.CURRENT_VERSION
        assertTrue("app/schemas/.../$cur.json missing: exportSchema/ksp arg not effective or file not generated", cur in schemaVersions())
        helper.createDatabase(dbName, cur).close()
        openRealRoom().use { it.openHelper.writableDatabase }
    }

    @Test fun historicalSchemaCoverageIsReported() {
        val have = schemaVersions()
        val missing = (JarvisMigrations.OLDEST_SUPPORTED_VERSION..JarvisMigrations.CURRENT_VERSION).filterNot { it in have }
        Log.w(tag, "schema versions present=$have; NOT covered by MigrationTestHelper=$missing")
        // Informational by design: versions 1-2 are covered by the raw-SQL tests below; the rest need
        // the committed historical schemas (tools/db/export_historical_schemas.sh).
    }

    @Test fun everyAvailableOlderSchemaMigratesToCurrentWithoutLosingData() {
        val older = schemaVersions().filter { it < JarvisMigrations.CURRENT_VERSION }
        for (v in older) {
            context.deleteDatabase(dbName)
            val before: Map<String, List<Map<String, String?>>> =
                helper.createDatabase(dbName, v).use { db ->
                    seedEveryTable(db)
                    dumpAllTables(db)
                }
            assertTrue("v$v: seeding produced no rows at all", before.values.any { it.isNotEmpty() })
            val migrated = helper.runMigrationsAndValidate(dbName, JarvisMigrations.CURRENT_VERSION, true, *JarvisMigrations.ALL)
            migrated.use { db ->
                val after = dumpAllTables(db)
                for ((table, rows) in before) {
                    assertTrue("v$v: table $table disappeared in the upgrade", table in after)
                    assertEquals("v$v: row count of $table changed", rows.size, after.getValue(table).size)
                    rows.forEachIndexed { i, oldRow ->
                        val newRow = after.getValue(table)[i]
                        for ((col, value) in oldRow) {
                            if (col in newRow) assertEquals("v$v: $table.$col changed", value, newRow[col])
                        }
                    }
                }
            }
        }
    }

    /** One row per real table (FTS shadow tables skipped), values derived from PRAGMA table_info. */
    private fun seedEveryTable(db: SupportSQLiteDatabase) {
        for (table in userTables(db)) {
            val cols = mutableListOf<Triple<String, String, Boolean>>() // name, type, notnull
            db.query("PRAGMA table_info(`$table`)").use { c ->
                while (c.moveToNext()) cols += Triple(c.getString(1), c.getString(2).uppercase(), c.getInt(3) == 1 || c.getInt(5) > 0)
            }
            if (cols.isEmpty()) continue
            val values = cols.map { (name, type, notNull) ->
                when {
                    !notNull && name.length % 2 == 0 -> "NULL" // keep some nullable columns NULL
                    type.contains("INT") -> "7"
                    type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") -> "1.5"
                    else -> "'seed-$table-$name'"
                }
            }
            db.execSQL("INSERT INTO `$table` (${cols.joinToString { "`${it.first}`" }}) VALUES (${values.joinToString()})")
        }
    }

    private fun userTables(db: SupportSQLiteDatabase): List<String> {
        val all = mutableListOf<Pair<String, String>>()
        db.query("SELECT name, sql FROM sqlite_master WHERE type='table' AND sql IS NOT NULL").use { c ->
            while (c.moveToNext()) all += c.getString(0) to c.getString(1)
        }
        val fts = all.filter { it.second.contains("VIRTUAL TABLE", ignoreCase = true) }.map { it.first }
        return all.map { it.first }.filter { n ->
            !n.startsWith("sqlite_") && n != "android_metadata" && n != "room_master_table" &&
                fts.none { f -> n.startsWith("${f}_") }
        }.sorted()
    }

    private fun dumpAllTables(db: SupportSQLiteDatabase): Map<String, List<Map<String, String?>>> =
        userTables(db).associateWith { t ->
            val rows = mutableListOf<Map<String, String?>>()
            db.query("SELECT * FROM `$t` ORDER BY rowid").use { c ->
                while (c.moveToNext()) {
                    rows += (0 until c.columnCount).associate { i -> c.getColumnName(i) to (if (c.isNull(i)) null else c.getString(i)) }
                }
            }
            rows
        }

    // ---------- 2. legacy chain from raw SQL (v1, v2) ----------

    private val v1Ddl = listOf(
        """CREATE TABLE `assistant_tasks` (`id` TEXT NOT NULL, `input` TEXT NOT NULL, `status` TEXT NOT NULL,
            `progress` INTEGER NOT NULL, `output` TEXT, `errorCode` TEXT, `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
    )
    private val v2ExtraDdl = listOf(
        """CREATE TABLE `documents` (`id` TEXT NOT NULL, `fileName` TEXT NOT NULL, `displayName` TEXT NOT NULL,
            `originalUri` TEXT NOT NULL, `vaultPath` TEXT, `mimeType` TEXT NOT NULL, `fileSize` INTEGER NOT NULL,
            `checksumSha256` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL,
            `parserVersion` INTEGER NOT NULL, `indexVersion` INTEGER NOT NULL, `status` TEXT NOT NULL,
            `tags` TEXT NOT NULL, `source` TEXT NOT NULL, `pageCount` INTEGER, `language` TEXT, `title` TEXT,
            `author` TEXT, PRIMARY KEY(`id`))""",
        """CREATE TABLE `document_chunks` (`id` TEXT NOT NULL, `documentId` TEXT NOT NULL, `text` TEXT NOT NULL,
            `section` TEXT NOT NULL, `page` INTEGER, `chunkIndex` INTEGER NOT NULL, `tokenEstimate` INTEGER NOT NULL,
            `documentTitle` TEXT NOT NULL, `fileName` TEXT NOT NULL, PRIMARY KEY(`id`))""",
        "CREATE INDEX `index_document_chunks_documentId` ON `document_chunks` (`documentId`)",
    )

    private fun rawDb(version: Int, ddl: List<String>, dml: List<String>) {
        context.deleteDatabase(dbName)
        val f = context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            ddl.forEach(db::execSQL); dml.forEach(db::execSQL); db.version = version
        }
    }

    private val taskRow =
        "INSERT INTO assistant_tasks VALUES ('t1','ricordami il latte','COMPLETED',100,'ok',NULL,1700000000000,1700000001000)"

    @Test fun version1UpgradesThroughTheWholeChainKeepingUserRows() {
        rawDb(1, v1Ddl, listOf(taskRow))
        assertEquals(DatabaseOpenGuard.Plan.Upgrade(1), DatabaseOpenGuard.preflight(context, dbName))
        openRealRoom().use { room ->
            val db = room.openHelper.writableDatabase // runs 1->15 and Room's schema validation of all tables
            assertEquals(JarvisMigrations.CURRENT_VERSION, db.version)
            db.query("SELECT input, status FROM assistant_tasks WHERE id='t1'").use {
                assertTrue(it.moveToFirst()); assertEquals("ricordami il latte", it.getString(0)); assertEquals("COMPLETED", it.getString(1))
            }
            for (t in listOf("documents", "nav_favorites", "automation_rules", "archive_items", "conversational_memory", "agent_responsibilities")) {
                db.query("SELECT COUNT(*) FROM `$t`").use { c -> assertTrue("missing $t", c.moveToFirst()) }
            }
        }
    }

    @Test fun version2UpgradesKeepingDocuments() {
        rawDb(
            2, v1Ddl + v2ExtraDdl,
            listOf(
                taskRow,
                "INSERT INTO documents VALUES ('d1','a.pdf','A','content://x','v/a','application/pdf',10,'abc',1,2,1,1,'READY','', 'CHAT_ATTACHMENT',3,'it','T','Au')",
                "INSERT INTO document_chunks VALUES ('c1','d1','testo','s',1,0,5,'T','a.pdf')",
            ),
        )
        openRealRoom().use { room ->
            val db = room.openHelper.writableDatabase
            db.query("SELECT title, pageCount FROM documents WHERE id='d1'").use {
                assertTrue(it.moveToFirst()); assertEquals("T", it.getString(0)); assertEquals(3, it.getInt(1))
            }
            db.query("SELECT text FROM document_chunks WHERE id='c1'").use { assertTrue(it.moveToFirst()); assertEquals("testo", it.getString(0)) }
        }
    }

    // ---------- 3. guard behaviour ----------

    @Test fun upgradeLeavesAPreMigrationSnapshotThatKeepsTheOldVersionAndRows() {
        rawDb(1, v1Ddl, listOf(taskRow))
        DatabaseOpenGuard.preflight(context, dbName)
        val dir = File(context.getDatabasePath(dbName).parentFile, DatabaseOpenGuard.SNAPSHOT_DIR)
        val snap = dir.listFiles().orEmpty().singleOrNull { it.name.startsWith("jarvis-v1-") }
        assertTrue("no pre-migration snapshot taken", snap != null)
        SQLiteDatabase.openDatabase(snap!!.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT COUNT(*) FROM assistant_tasks", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test fun snapshotContainsDataStillSittingInTheWalAndIsVerified() {
        context.deleteDatabase(dbName)
        val f = context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }
        val live = SQLiteDatabase.openOrCreateDatabase(f, null)
        try {
            live.enableWriteAheadLogging()
            v1Ddl.forEach(live::execSQL)
            live.version = 1
            live.execSQL(taskRow)
            // This connection stays open: the row above lives in jarvis.db-wal, NOT yet in jarvis.db.
            val snap = PremigrationSnapshotter(
                File(f.parentFile, DatabaseOpenGuard.SNAPSHOT_DIR), AndroidDbSnapshotEngine,
            ).ensureSnapshot(f, 1)
            val facts = AndroidDbSnapshotEngine.facts(snap)
            assertTrue(facts.integrityOk)
            assertEquals(1, facts.userVersion)
            assertEquals(1L, facts.rowCounts["assistant_tasks"])
            assertTrue(File(snap.parentFile, snap.name + PremigrationSnapshotter.PART).exists().not())
        } finally {
            live.close()
        }
    }

    @Test fun ifTheSnapshotCannotBeMadeTheUpgradeIsBlockedAndTheDatabaseIsUntouched() {
        rawDb(1, v1Ddl, listOf(taskRow))
        val f = context.getDatabasePath(dbName)
        // A regular FILE where the snapshot directory must be: the copy is impossible.
        File(f.parentFile, DatabaseOpenGuard.SNAPSHOT_DIR).apply { deleteRecursively(); writeText("not a directory") }
        val before = sha256(f)
        try {
            DatabaseOpenGuard.preflight(context, dbName)
            fail("the upgrade must not proceed without a verified snapshot")
        } catch (_: SnapshotFailedException) {
        } finally {
            File(f.parentFile, DatabaseOpenGuard.SNAPSHOT_DIR).delete()
        }
        assertEquals(before, sha256(f))
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(1, it.version) }
    }

    @Test fun aNewerDatabaseIsRefusedAndLeftByteForByteUntouched() {
        rawDb(JarvisMigrations.CURRENT_VERSION + 5, v1Ddl, listOf(taskRow))
        val f = context.getDatabasePath(dbName)
        val before = sha256(f)
        try {
            DatabaseOpenGuard.preflight(context, dbName)
            fail("a database newer than this build must be refused")
        } catch (_: DatabaseOpenGuard.UnsupportedDatabaseVersionException) {
        }
        assertTrue(f.exists())
        assertEquals(before, sha256(f))
        assertFalse(File(f.parentFile, DatabaseOpenGuard.SNAPSHOT_DIR).exists() && File(f.parentFile, DatabaseOpenGuard.SNAPSHOT_DIR).list().orEmpty().isNotEmpty())
    }

    @Test fun aFreshInstallCreatesTheCurrentSchema() {
        context.deleteDatabase(dbName)
        assertEquals(DatabaseOpenGuard.Plan.Fresh, DatabaseOpenGuard.preflight(context, dbName))
        openRealRoom().use { assertEquals(JarvisMigrations.CURRENT_VERSION, it.openHelper.writableDatabase.version) }
    }

    private fun openRealRoom(): JarvisDatabase =
        Room.databaseBuilder(context, JarvisDatabase::class.java, dbName)
            .addMigrations(*JarvisMigrations.ALL) // NO destructive fallback, exactly like production
            .build()

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
