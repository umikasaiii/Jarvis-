package com.simone.jarvismobile.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * STEP 2 — orchestration of the verified pre-migration snapshot, with a fake SQLite engine
 * (real SQLite/WAL behaviour is covered by JarvisMigrationInstrumentedTest on a device).
 */
class PremigrationSnapshotterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val good = DbFacts(userVersion = 14, integrityOk = true, rowCounts = mapOf("archive_items" to 3L, "automation_rules" to 2L))

    /** Facts are looked up by file name; `snapshotInto` copies bytes and (optionally) misbehaves. */
    private class FakeEngine(val facts: MutableMap<String, DbFacts>, val srcName: String) : DbSnapshotEngine {
        var mode: String = "ok"
        var writes = 0
        override fun snapshotInto(source: File, target: File) {
            writes++
            when (mode) {
                "ioerror" -> { target.writeBytes(ByteArray(10)); throw IOException("No space left on device") }
                "partial" -> { target.writeBytes(ByteArray(10)); facts[target.name] = facts.getValue(srcName).copy(rowCounts = mapOf("archive_items" to 1L)) }
                "badintegrity" -> { target.writeBytes(source.readBytes()); facts[target.name] = facts.getValue(srcName).copy(integrityOk = false) }
                "wrongversion" -> { target.writeBytes(source.readBytes()); facts[target.name] = facts.getValue(srcName).copy(userVersion = 99) }
                else -> { target.writeBytes(source.readBytes()); facts[target.name] = facts.getValue(srcName) }
            }
        }
        // The snapshot is verified as `*.part` and later as its renamed final file: both names resolve.
        override fun facts(db: File): DbFacts =
            facts[db.name] ?: facts[db.name + PremigrationSnapshotter.PART] ?: throw IOException("unreadable ${db.name}")
    }

    private fun setup(srcFacts: DbFacts = good): Triple<File, File, FakeEngine> {
        val root = tmp.newFolder()
        val db = File(root, "jarvis.db").also { it.writeBytes(ByteArray(2048) { i -> i.toByte() }) }
        val dir = File(root, "premigration")
        return Triple(db, dir, FakeEngine(mutableMapOf(db.name to srcFacts), db.name))
    }

    private fun snapshotter(dir: File, e: FakeEngine, space: Long = Long.MAX_VALUE, now: Long = 5_000L, keep: Int = 2) =
        PremigrationSnapshotter(dir, e, usableSpace = { space }, now = { now }, keep = keep)

    @Test fun happyPathProducesAVerifiedFinalFileAndNoPartLeftover() {
        val (db, dir, e) = setup()
        val s = snapshotter(dir, e).ensureSnapshot(db, 14)
        assertEquals(DatabaseOpenGuard.snapshotName(14, 5_000L), s.name)
        assertTrue(s.isFile)
        assertEquals(db.readBytes().toList(), s.readBytes().toList())
        assertTrue(dir.list().orEmpty().none { it.endsWith(".part") })
    }

    @Test fun interruptedWriteLeavesNoSnapshotAndFailsClosed() {
        val (db, dir, e) = setup(); e.mode = "ioerror"
        try { snapshotter(dir, e).ensureSnapshot(db, 14); fail("must fail closed") } catch (_: SnapshotFailedException) {}
        assertEquals("no valid snapshot and no .part may remain", emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test fun anIncompleteCopyIsNeverAccepted() {
        for (mode in listOf("partial", "badintegrity", "wrongversion")) {
            val (db, dir, e) = setup(); e.mode = mode
            try { snapshotter(dir, e).ensureSnapshot(db, 14); fail("$mode accepted") } catch (_: SnapshotFailedException) {}
            assertEquals("$mode left files", emptyList<String>(), dir.list().orEmpty().toList())
        }
    }

    @Test fun aFailedAttemptNeverReplacesOrPrunesAGoodOlderSnapshot() {
        val (db, dir, e) = setup()
        val first = snapshotter(dir, e, now = 1_000L).ensureSnapshot(db, 14)
        db.setLastModified(first.lastModified() + 60_000)        // db changed after the first copy
        e.mode = "partial"
        try { snapshotter(dir, e, now = 2_000L, keep = 1).ensureSnapshot(db, 14); fail() } catch (_: SnapshotFailedException) {}
        assertEquals(listOf(first.name), dir.list().orEmpty().toList())
        assertEquals(db.readBytes().toList(), first.readBytes().toList())
    }

    @Test fun insufficientSpaceBlocksBeforeWritingAnything() {
        val (db, dir, e) = setup()
        try { snapshotter(dir, e, space = 1024).ensureSnapshot(db, 14); fail() } catch (x: SnapshotFailedException) {
            assertTrue(x.message, x.message!!.contains("spazio insufficiente"))
        }
        assertEquals(0, e.writes)
        assertTrue(db.exists())
    }

    @Test fun anUnchangedDatabaseReusesItsVerifiedSnapshotInsteadOfCopyingAgain() {
        val (db, dir, e) = setup()
        val a = snapshotter(dir, e, now = db.lastModified() + 10).ensureSnapshot(db, 14)
        val b = snapshotter(dir, e, now = db.lastModified() + 20).ensureSnapshot(db, 14)
        assertEquals(a.name, b.name)
        assertEquals(1, e.writes)
    }

    @Test fun aCorruptOriginalIsNeverSnapshottedOrMigrated() {
        val (db, dir, e) = setup(good.copy(integrityOk = false))
        try { snapshotter(dir, e).ensureSnapshot(db, 14); fail() } catch (_: SnapshotFailedException) {}
        assertEquals(0, e.writes)
    }

    @Test fun leftoverPartFilesFromAKilledProcessAreRemoved() {
        val (db, dir, e) = setup()
        dir.mkdirs(); File(dir, "jarvis-v14-1.db.part").writeBytes(ByteArray(5))
        snapshotter(dir, e).ensureSnapshot(db, 14)
        assertFalse(dir.list().orEmpty().any { it.endsWith(".part") })
    }

    @Test fun onlyTheNewestKeepSnapshotsSurviveAfterASuccess() {
        val (db, dir, e) = setup()
        dir.mkdirs()
        listOf(100L, 200L).forEach { File(dir, DatabaseOpenGuard.snapshotName(13, it)).writeBytes(ByteArray(1)) }
        snapshotter(dir, e, now = 9_000L, keep = 2).ensureSnapshot(db, 14)
        assertEquals(
            setOf(DatabaseOpenGuard.snapshotName(13, 200L), DatabaseOpenGuard.snapshotName(14, 9_000L)),
            dir.list().orEmpty().toSet(),
        )
    }

    @Test fun databaseVersionChangedUnderUsFailsClosed() {
        val (db, dir, e) = setup()
        try { snapshotter(dir, e).ensureSnapshot(db, 13); fail() } catch (_: SnapshotFailedException) {}
    }
}
