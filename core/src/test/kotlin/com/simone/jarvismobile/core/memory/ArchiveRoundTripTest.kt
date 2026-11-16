package com.simone.jarvismobile.core.memory

import com.simone.jarvismobile.core.storage.AtomicTextFile
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reproduction of the storage-integrity review: what a rewrite of a MIXED archive keeps, normalises or loses. */
class ArchiveRoundTripTest {
    private fun rec(id: String, text: String, at: Long = 1L) = MemoryRecord(id = id, text = text, createdAt = at)
    private fun line(r: MemoryRecord) = MemoryRecordCodec.render(listOf(r)).lines().first { it.startsWith("<!--") }

    private val valid1 = line(rec("v1", "Ho una moto rossa", 1))
    private val valid2 = line(rec("v2", "Abito a Torino", 2))
    private val paddedBroken = """   <!-- jarvis-memory-v2:{"id":"p","text":"con spazi","createdAt": -->   """
    private val truncated = """<!-- jarvis-memory-v2:{"id":"t","text":"troncato","kind":"PERMANENT","createdAt":3"""
    private val badPrefix = """<!-- jarvis-memory-v1:{"id":"x","text":"prefisso sbagliato","createdAt":4} -->"""
    private val stranger = "Appunto scritto a mano da Obsidian: non toccare"
    private val sameIdOther = line(rec("v1", "Stesso id ma contenuto DIVERSO", 5))
    private val future = """<!-- jarvis-memory-v2:{"id":"fut","text":"Ho un gatto","kind":"PERMANENT","createdAt":6,"source":"FROM_THE_FUTURE","novitaFutura":{"a":1}} -->"""
    private val legacy = "- [2024-05-01 10:00] Compro il pane il sabato"

    private val archive = listOf("# Memoria di JARVIS", "", legacy, "", valid1, "", paddedBroken, truncated, valid2, badPrefix, stranger, sameIdOther, future, "").joinToString("\n")

    @Test fun parsingClassifiesEveryLineAndLosesNothingSilently() {
        val r = MemoryRecordCodec.parseDetailed(archive)
        assertEquals(setOf("v1", "v2", "fut"), r.records.map { it.id }.filterNot { it.startsWith("legacy-") }.toSet())
        assertTrue(r.records.any { it.id.startsWith("legacy-") && "pane" in it.text })
        // everything the app cannot decode or does not recognise, WITH its original spaces, in file order
        assertEquals(listOf(paddedBroken, truncated, badPrefix, stranger, sameIdOther), r.damaged)
        assertFalse(r.isIntact)
    }

    @Test fun addingAMemoryAndRewritingKeepsAllUnderstoodDataAndEveryUnknownLineVerbatim() {
        val r = MemoryRecordCodec.parseDetailed(archive)
        val rewritten = MemoryRecordCodec.render(r.records + rec("new", "Nuovo dato", 9), r.damaged, r.origins)
        r.damaged.forEach { assertTrue(it in rewritten.lines(), "kept byte for byte (incl. spaces): [$it]") }
        // the future enum value and the unknown key are written back, not flattened to the default
        assertTrue("FROM_THE_FUTURE" in rewritten && "novitaFutura" in rewritten, rewritten)
        val again = MemoryRecordCodec.parseDetailed(rewritten)
        assertEquals(r.records.map { it.id }.toSet() + "new", again.records.map { it.id }.toSet())
        assertEquals(r.damaged, again.damaged.sortedBy { r.damaged.indexOf(it) }, "nothing new is lost and nothing is duplicated")
        // a second rewrite is stable
        assertEquals(rewritten, MemoryRecordCodec.render(again.records, again.damaged, again.origins))
    }

    @Test fun twoRecordsWithTheSameIdAndDifferentContentAreBothKept() {
        val r = MemoryRecordCodec.parseDetailed(listOf(valid1, sameIdOther).joinToString("\n"))
        assertEquals("Ho una moto rossa", r.records.single().text, "first one wins as the live record")
        assertEquals(listOf(sameIdOther), r.damaged, "the other one is data: kept verbatim, not dropped by distinctBy")
    }

    @Test fun aBulletAfterARecordIsAnEchoOnlyIfItRepeatsIt() {
        val echo = MemoryRecordCodec.parseDetailed(valid1 + "\n- 🔒 Ho una moto rossa\n")
        assertEquals(1, echo.records.size); assertTrue(echo.isIntact, "a formatting-only echo is consumed")
        val other = MemoryRecordCodec.parseDetailed(valid1 + "\n- Tutt'altro appunto dell'utente\n")
        assertEquals(1, other.records.size, "never merged into the record")
        assertEquals(listOf("- Tutt'altro appunto dell'utente"), other.damaged, "but not dropped either")
    }

    @Test fun anExactDuplicateIsHarmlessAndDropped() {
        val r = MemoryRecordCodec.parseDetailed(listOf(valid1, valid1).joinToString("\n"))
        assertEquals(1, r.records.size); assertTrue(r.isIntact)
    }

    @Test fun changingAFieldOfAFutureRecordKeepsTheUnknownParts() {
        val r = MemoryRecordCodec.parseDetailed(future)
        val changed = r.records.single().copy(text = "Ho due gatti")
        val out = MemoryRecordCodec.render(listOf(changed), emptyList(), r.origins)
        assertTrue("Ho due gatti" in out && "FROM_THE_FUTURE" in out && "novitaFutura" in out, out)
    }

    @Test fun validAndLegacyArchivesStillLoadAndRenderExactlyAsBefore() {
        val clean = MemoryRecordCodec.render(listOf(rec("a", "Primo", 1), rec("b", "Secondo", 2)))
        val r = MemoryRecordCodec.parseDetailed(clean)
        assertTrue(r.isIntact && r.records.size == 2)
        assertEquals(clean, MemoryRecordCodec.render(r.records, r.damaged, r.origins), "a clean archive rewrites to the same content")
        assertEquals(2, MemoryRecordCodec.parseDetailed("- [2024-05-01 10:00] uno\n- due\n").records.size)
    }

    @Test fun crlfIsAcceptedAndOnlyTheLineTerminatorChanges() {
        val r = MemoryRecordCodec.parseDetailed(archive.replace("\n", "\r\n"))
        assertEquals(listOf(paddedBroken, truncated, badPrefix, stranger, sameIdOther), r.damaged)
    }

    // ---- AtomicTextFile contract ----
    private val dir: File = createTempDirectory("atomic").toFile().also { it.deleteOnExit() }

    @Test fun aGarbledOrEmptyMainFileNeverOverwritesTheLastGoodBackup() {
        val target = File(dir, "m.md")
        val f = { AtomicTextFile(target, worthBackingUp = { MemoryRecordCodec.parseDetailed(it).records.isNotEmpty() }) }
        val good = MemoryRecordCodec.render(listOf(rec("a", "Dato buono")))
        f().write(good); f().write(good + "\n") // second write backs up the good first version
        assertEquals(good, f().backup.readText())
        target.writeText("@@@ garbled @@@") // the main file is damaged on disk
        f().write(good + "\n\n")
        assertEquals(good, f().backup.readText(), "the backup is still the last version that held records")
        target.writeText("")
        f().write(good + "\n\n\n")
        assertEquals(good, f().backup.readText())
    }

    @Test fun aMissingMainFileIsRecoveredFromTheBackupNotTreatedAsAnEmptyArchive() {
        val target = File(dir, "n.md")
        val f = AtomicTextFile(target)
        f.write("uno"); f.write("due")
        target.delete()
        assertEquals("uno", f.readOrNull(), "the last good copy, not an empty archive")
        assertEquals("", AtomicTextFile(File(dir, "never-existed.md")).readOrNull())
    }
}
