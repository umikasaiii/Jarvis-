package com.simone.jarvismobile.archive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.background.JarvisDatabase
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord
import com.simone.jarvismobile.core.notes.NoteSaveDecision
import com.simone.jarvismobile.core.notes.NoteSavePolicy
import com.simone.jarvismobile.core.notes.NoteSource
import com.simone.jarvismobile.core.notes.NotesFeed
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.memory.EmbeddingRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * BETA 3 — the Note store on a REAL Room database (file-backed, so "close and reopen the app"
 * is exercised for real). Runs on an emulator via the "DB migration tests (emulator)" workflow;
 * the main CI only compiles it. Covers create / edit+reread / delete / search / folder move /
 * persistence across reopen / no duplicates / merge with Memoria records / error paths.
 */
@RunWith(AndroidJUnit4::class)
class NotesStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "beta3_notes_test.db"
    private lateinit var db: JarvisDatabase

    private fun open(): JarvisDatabase =
        Room.databaseBuilder(context, JarvisDatabase::class.java, dbName).allowMainThreadQueries().build()

    private fun repo(d: JarvisDatabase) = ArchiveRepository(d.archiveDao(), EmbeddingRepository(context, SettingsRepository(context)))

    @Before fun setUp() {
        context.deleteDatabase(dbName)
        db = open()
    }

    @After fun tearDown() {
        runCatching { db.close() }
        context.deleteDatabase(dbName)
    }

    @Test fun createEditRereadAndDelete() = runBlocking {
        val r = repo(db)
        val note = r.create(ArchiveKind.NOTE, "Idee", "uno", folder = "Lavoro")!!
        assertEquals("uno", r.get(note.id)!!.content)
        r.update(note.id, content = "uno\ndue")
        assertEquals("uno\ndue", r.get(note.id)!!.content)
        assertTrue(r.delete(note.id))
        assertNull(r.get(note.id))
        assertFalse(r.delete(note.id)) // second delete is a clean "nothing to delete"
    }

    @Test fun editingOneNeverTouchesAnother() = runBlocking {
        val r = repo(db)
        val a = r.create(ArchiveKind.NOTE, "A", "aaa")!!
        val b = r.create(ArchiveKind.NOTE, "B", "bbb")!!
        r.update(a.id, content = "aaa2", pinned = true)
        val bAfter = r.get(b.id)!!
        assertEquals("bbb", bAfter.content); assertFalse(bAfter.pinned)
    }

    @Test fun searchFindsByTitleAndContent() = runBlocking {
        val r = repo(db)
        r.create(ArchiveKind.NOTE, "Moto", "cambiare olio")
        r.create(ArchiveKind.NOTE, "Spesa", "latte e pane")
        assertEquals(1, r.findByText("olio", ArchiveKind.NOTE).size)
        assertEquals(1, r.findByText("spesa", ArchiveKind.NOTE).size)
        assertEquals(0, r.findByText("inesistente", ArchiveKind.NOTE).size)
    }

    @Test fun movingToAFolderKeepsContentAndId() = runBlocking {
        val r = repo(db)
        val n = r.create(ArchiveKind.NOTE, "N", "testo")!!
        val moved = r.update(n.id, folder = "Casa")!!
        assertEquals(n.id, moved.id); assertEquals("testo", moved.content); assertEquals("Casa", moved.folder)
        r.update(n.id, folder = "")
        assertEquals("", r.get(n.id)!!.folder)
    }

    @Test fun notesSurviveClosingAndReopeningTheDatabase() = runBlocking {
        val first = repo(db)
        val ids = (1..5).map { first.create(ArchiveKind.NOTE, "Nota $it", "contenuto $it", folder = if (it % 2 == 0) "A" else "")!!.id }
        db.close()
        db = open()
        val again = repo(db).all().filter { it.kind == ArchiveKind.NOTE }
        assertEquals(ids.toSet(), again.map { it.id }.toSet())
        assertEquals(5, again.size) // nothing lost, nothing duplicated
    }

    @Test fun deletingOneNoteLeavesTheRest() = runBlocking {
        val r = repo(db)
        val keep = r.create(ArchiveKind.NOTE, "Resta", "x")!!
        val gone = r.create(ArchiveKind.NOTE, "Via", "y")!!
        r.delete(gone.id)
        assertEquals(listOf(keep.id), r.all().map { it.id })
    }

    @Test fun mergedWithMemoriaRecordsWithoutDuplicatesOrCopies() = runBlocking {
        val r = repo(db)
        r.create(ArchiveKind.NOTE, "Archivio nota", "a")
        val memory = listOf(
            MemoryRecord(id = "m1", text = "Ricordo uno", kind = MemoryKind.PERMANENT, createdAt = 1),
            MemoryRecord(id = "t1", text = "Solo conversazione", kind = MemoryKind.TEMPORARY, createdAt = 1),
        )
        val before = r.all().size
        val merged = NotesFeed.merge(r.all(), memory)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.ref.source == NoteSource.MEMORIA && it.ref.id == "m1" })
        assertTrue(merged.none { it.ref.id == "t1" })
        assertEquals(before, r.all().size) // merging never writes a copy
    }

    @Test fun autosavePolicyDoesNotRewriteAnUnchangedStoredNote() = runBlocking {
        val r = repo(db)
        val n = r.create(ArchiveKind.NOTE, "Titolo", "corpo")!!
        val entry = NotesFeed.fromArchive(r.get(n.id)!!)!!
        assertEquals(NoteSaveDecision.Skip, NoteSavePolicy.decide(entry, entry.title, entry.content, entry.folder, entry.pinned, entry.theme, entry.spacing))
    }

    @Test fun writesToAClosedDatabaseFailCleanlyInsteadOfCrashing() = runBlocking {
        val r = repo(db)
        db.close()
        assertNull(r.create(ArchiveKind.NOTE, "X", "y")) // reported as a failure, not an exception
    }

    @Test fun blankTitleIsRejectedAtTheStore() = runBlocking {
        assertNull(repo(db).create(ArchiveKind.NOTE, "   ", "x"))
        assertNotNull(repo(db).create(ArchiveKind.NOTE, "ok", "x"))
    }
}
