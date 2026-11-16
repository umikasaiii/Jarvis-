package com.simone.jarvismobile.core.memory

import com.simone.jarvismobile.core.storage.AtomicTextFile
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStorageSafetyTest {
    private fun rec(id: String, text: String) = MemoryRecord(id = id, text = text, createdAt = id.hashCode().toLong())
    private val dir: File = createTempDirectory("mem").toFile().also { it.deleteOnExit() }
    private fun file(name: String) = File(dir, name)

    // ---- parsing: empty vs legacy vs future vs damaged ----
    @Test fun aTrulyEmptyArchiveIsIntactAndEmpty() {
        val r = MemoryRecordCodec.parseDetailed("")
        assertTrue(r.records.isEmpty() && r.isIntact)
        assertTrue(MemoryRecordCodec.parseDetailed("# Memoria di JARVIS\n\n").isIntact)
    }

    @Test fun legacyBulletsLoadAndAreNotDamage() {
        val r = MemoryRecordCodec.parseDetailed("- [2024-05-01 10:00] Ho una moto\n- Un altro appunto\n")
        assertEquals(2, r.records.size); assertTrue(r.isIntact)
    }

    @Test fun anUnknownFutureValueLoadsWithADefaultAndIsNotDamage() {
        val raw = """<!-- jarvis-memory-v2:{"id":"f","text":"Ho un gatto","kind":"PERMANENT","createdAt":6,"source":"FROM_THE_FUTURE","extra":1} -->"""
        val r = MemoryRecordCodec.parseDetailed(raw)
        assertEquals(1, r.records.size); assertTrue(r.isIntact)
        assertEquals(MemorySource.USER_STATEMENT, r.records.single().source)
    }

    @Test fun aCorruptStructuredRecordAmongValidOnesIsReportedNotDropped() {
        val good1 = MemoryRecordCodec.render(listOf(rec("a", "Primo")))
        val good2 = MemoryRecordCodec.render(listOf(rec("c", "Terzo"))).removePrefix("# Memoria di JARVIS\n\n")
        val bad = """<!-- jarvis-memory-v2:{"id":"b","text":"Segreto del secondo","kind":"PERMANENT","createdAt": -->"""
        val r = MemoryRecordCodec.parseDetailed(good1 + bad + "\n\n" + good2)
        assertEquals(setOf("a", "c"), r.records.map { it.id }.toSet())
        assertEquals(listOf(bad), r.damaged)
        assertFalse(r.isIntact)
    }

    @Test fun aTruncatedLineIsDamageToo() {
        val r = MemoryRecordCodec.parseDetailed("""<!-- jarvis-memory-v2:{"id":"t","text":"x","kind":"PERMANENT","createdAt":1""")
        assertEquals(1, r.damaged.size)
    }

    @Test fun aRewriteKeepsDamagedLinesVerbatimAndTheValidRecords() {
        val bad = """<!-- jarvis-memory-v2:{"id":"b","text":"Da recuperare a mano","createdAt": -->"""
        val parsed = MemoryRecordCodec.parseDetailed(MemoryRecordCodec.render(listOf(rec("a", "Primo"))) + bad)
        // the app now adds a record: the rewrite must carry the undecodable line along
        val rewritten = MemoryRecordCodec.render(parsed.records + rec("n", "Nuovo"), parsed.damaged)
        assertTrue(bad in rewritten, "the damaged line is still there, byte for byte")
        val again = MemoryRecordCodec.parseDetailed(rewritten)
        assertEquals(setOf("a", "n"), again.records.map { it.id }.toSet()); assertEquals(listOf(bad), again.damaged)
    }

    @Test fun m1FieldsRoundTripAndOldFilesStillLoad() {
        val r = rec("x", "Il mio peso è 80 kg").copy(source = MemorySource.USER_STATEMENT, status = RecordStatus.SUPERSEDED, supersededBy = "y", subjectKey = "peso")
        val back = MemoryRecordCodec.parseDetailed(MemoryRecordCodec.render(listOf(r))).records.single()
        assertEquals(RecordStatus.SUPERSEDED, back.status); assertEquals("peso", back.subjectKey)
    }

    // ---- atomic file ----
    @Test fun noFileIsEmptyNotAnError() {
        assertEquals("", AtomicTextFile(file("none.md")).readOrNull())
    }

    @Test fun aWriteIsCommittedAndTheOldVersionKeptAsBackup() {
        val f = AtomicTextFile(file("a.md"))
        f.write("uno"); f.write("due")
        assertEquals("due", f.readOrNull()); assertEquals("uno", f.backup.readText())
    }

    @Test fun anInterruptedWriteLeavesTheOldArchiveIntact() {
        val target = file("b.md")
        AtomicTextFile(target).write("originale completo")
        val crashing = AtomicTextFile(target, beforeCommit = { throw IOException("process killed before the rename") })
        assertFailsWith<IOException> { crashing.write("nuovo contenuto") }
        assertEquals("originale completo", AtomicTextFile(target).readOrNull(), "recovery after restart: the archive is the old one, complete")
    }

    @Test fun aStaleTempFileFromACrashDoesNotBreakTheNextWrite() {
        val target = file("c.md")
        File(target.path + ".tmp").writeText("residuo parziale")
        val f = AtomicTextFile(target)
        f.write("pulito")
        assertEquals("pulito", f.readOrNull())
    }

    @Test fun anUnreadableFileIsReportedAsNullNotAsEmpty() {
        val dirAsFile = File(dir, "d.md").also { it.mkdirs() } // exists but cannot be read as text
        assertNull(AtomicTextFile(dirAsFile).readOrNull())
    }
}
