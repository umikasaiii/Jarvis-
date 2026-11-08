package com.simone.jarvismobile.core.notes

import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesFeedTest {
    private fun note(id: String, title: String = "T$id", content: String = "c$id", folder: String = "", pinned: Boolean = false, updated: Long = 10, kind: ArchiveKind = ArchiveKind.NOTE) =
        ArchiveItem(id = id, kind = kind, title = title, content = content, folder = folder, pinned = pinned, createdAt = 1, updatedAt = updated)

    private fun mem(id: String, text: String = "ricordo $id", kind: MemoryKind = MemoryKind.PERMANENT, category: String = "", updated: Long = 5) =
        MemoryRecord(id = id, text = text, kind = kind, createdAt = 1, updatedAt = updated, category = category)

    @Test fun archiveNoteKeepsOriginalIdAndSource() {
        val e = NotesFeed.fromArchive(note("a1"))!!
        assertEquals(NoteRef(NoteSource.ARCHIVE, "a1"), e.ref)
        assertEquals("Ta1", e.title)
    }

    @Test fun toWatchItemsAreNotNotes() {
        assertNull(NotesFeed.fromArchive(note("w", kind = ArchiveKind.TO_WATCH)))
    }

    @Test fun temporaryConversationMemoryIsNeverShownAsANote() {
        assertNull(NotesFeed.fromMemory(mem("t", kind = MemoryKind.TEMPORARY)))
    }

    @Test fun sensitiveMemoryIsShownButFlagged() {
        val e = NotesFeed.fromMemory(mem("s", kind = MemoryKind.SENSITIVE))!!
        assertTrue(e.sensitive)
        assertFalse(NotesFeed.fromMemory(mem("p"))!!.sensitive)
    }

    @Test fun memoryRecordProvenanceAndNoPin() {
        val e = NotesFeed.fromMemory(mem("m1", text = "# Moto\nolio", category = "Moto"))!!
        assertEquals(NoteRef(NoteSource.MEMORIA, "m1"), e.ref)
        assertEquals("Moto", e.title)
        assertEquals("Moto", e.folder)
        assertFalse(e.canPin)
    }

    @Test fun sameIdInTwoStoresIsNotMergedAway() {
        val merged = NotesFeed.merge(listOf(note("x")), listOf(mem("x")))
        assertEquals(2, merged.size)
        assertEquals(setOf("ARCHIVE:x", "MEMORIA:x"), merged.map { it.ref.key }.toSet())
    }

    @Test fun mergeNeverDuplicatesTheSameRef() {
        val merged = NotesFeed.merge(listOf(note("x"), note("x")), listOf(mem("m"), mem("m")))
        assertEquals(2, merged.size)
    }

    @Test fun sortedPinnedFirstThenNewest() {
        val list = NotesFeed.merge(
            listOf(note("old", updated = 1), note("new", updated = 9), note("pin", updated = 2, pinned = true)),
            listOf(mem("m", updated = 5)),
        )
        assertEquals(listOf("pin", "new", "m", "old"), NotesFeed.sorted(list).map { it.ref.id })
    }

    @Test fun searchMatchesTitleAndContentCaseInsensitive() {
        val list = NotesFeed.merge(listOf(note("1", title = "Moto", content = "olio"), note("2", title = "Spesa", content = "latte")), listOf(mem("m", text = "assicurazione MOTO")))
        assertEquals(setOf("1", "m"), NotesFeed.filter(list, "moto", null).map { it.ref.id }.toSet())
    }

    @Test fun folderFilterAndUnfiled() {
        val list = NotesFeed.merge(listOf(note("1", folder = "Lavoro"), note("2")), emptyList())
        assertEquals(listOf("1"), NotesFeed.filter(list, "", "Lavoro").map { it.ref.id })
        assertEquals(listOf("2"), NotesFeed.filter(list, "", NotesFeed.UNFILED).map { it.ref.id })
        assertEquals(listOf("Lavoro"), NotesFeed.folders(list))
    }

    @Test fun titleFromFirstLineStripsMarkup() {
        assertEquals("Idee", NotesFeed.titleFrom("\n## Idee\ncorpo"))
        assertEquals("Nota senza titolo", NotesFeed.titleFrom("   "))
        assertEquals(60, NotesFeed.titleFrom("x".repeat(200)).length)
    }

    @Test fun reloadingTheSameStoresIsStable() {
        val a = listOf(note("1"), note("2")); val m = listOf(mem("m"))
        assertEquals(NotesFeed.merge(a, m), NotesFeed.merge(a, m))
    }

    // --- save policy -----------------------------------------------------
    @Test fun newEmptyNoteIsNeverCreated() {
        assertEquals(NoteSaveDecision.Skip, NoteSavePolicy.decide(null, "", "  ", "", false, "", ""))
    }

    @Test fun blankTitleWithTextStillSavesAndDerivesTitle() {
        val d = NoteSavePolicy.decide(null, "", "Comprare fissativo\nsubito", "", false, "", "")
        assertEquals(NoteSaveDecision.Save("Comprare fissativo", "Comprare fissativo\nsubito"), d)
    }

    @Test fun unchangedNoteIsNotRewritten() {
        val e = NotesFeed.fromArchive(note("1", title = "T", content = "c"))!!
        assertEquals(NoteSaveDecision.Skip, NoteSavePolicy.decide(e, "T", "c", "", false, "", ""))
    }

    @Test fun editedContentIsSaved() {
        val e = NotesFeed.fromArchive(note("1", title = "T", content = "c"))!!
        assertTrue(NoteSavePolicy.decide(e, "T", "c2", "", false, "", "") is NoteSaveDecision.Save)
    }

    @Test fun folderMoveAloneIsASave() {
        val e = NotesFeed.fromArchive(note("1", title = "T", content = "c"))!!
        assertTrue(NoteSavePolicy.decide(e, "T", "c", "Lavoro", false, "", "") is NoteSaveDecision.Save)
    }
}
