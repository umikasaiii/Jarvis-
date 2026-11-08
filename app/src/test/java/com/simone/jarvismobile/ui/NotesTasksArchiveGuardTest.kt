package com.simone.jarvismobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BETA 3/8 — source-level guards (JVM, no Android runtime) pinning the wiring of the unified
 * Note screen, the safe editor and the task-notification fix. They prove the code is wired and
 * what it cannot do (copy notes between stores); they do NOT prove the UI behaves on a device.
 */
class NotesTasksArchiveGuardTest {
    private val root: File = listOf(File("src/main/java/com/simone/jarvismobile"), File("app/src/main/java/com/simone/jarvismobile"))
        .firstOrNull { it.isDirectory } ?: error("app sources not found from ${File(".").absolutePath}")

    private fun src(rel: String) = File(root, rel).also { check(it.isFile) { "missing $rel" } }.readText()

    @Test fun noteScreenIsReachableFromHomeSettingsAndArchive() {
        val app = src("ui/JarvisApp.kt")
        assertTrue("Overlay.NOTES -> NotesScreen" in app)
        assertTrue("onOpenNotes = { overlay = Overlay.NOTES }" in app)
        assertTrue("onOpenNotes" in src("ui/dashboard/DashboardScreen.kt"))
        assertTrue("onOpenNotes" in src("ui/settings/SettingsScreen.kt"))
        assertTrue("onOpenNotes()" in src("ui/archive/ArchiveScreen.kt")) // Archivio › Appunti opens the same screen
    }

    @Test fun archivioNoLongerHasItsOwnSecondNotesList() {
        val a = src("ui/archive/ArchiveScreen.kt")
        assertFalse("private fun NotesTab" in a)
        assertFalse("private fun NoteEditorScreen" in a)
    }

    @Test fun editorAutosavesAndNeverClosesOnAFailedSave() {
        val e = src("ui/archive/NoteEditor.kt")
        assertTrue("NoteSavePolicy.decide" in e)
        assertTrue(".debounce(" in e)
        assertTrue("LifecycleEventEffect(Lifecycle.Event.ON_PAUSE)" in e)
        assertTrue("if (persist()) onClose()" in e)
        assertTrue("saveLock.withLock" in e)
        assertTrue("Eliminare la nota?" in e) // deletion asks first
    }

    @Test fun noteViewModelNeverCopiesContentBetweenStores() {
        val vm = src("ui/archive/NotesViewModel.kt")
        // creating happens only for a brand-new note (ref == null); editing routes to the owning store
        assertTrue(Regex("""if \(ref == null\) \{\s*val created = archive\.create""").containsMatchIn(vm))
        assertFalse("memory.remember(" in vm)
        assertTrue("NotesFeed.merge(" in vm)
    }

    @Test fun deletingAFolderOnlyUnfilesItsNotes() {
        val vm = src("ui/archive/NotesViewModel.kt")
        val body = vm.substringAfter("fun deleteFolder").substringBefore("\n    }\n")
        assertTrue("move(it, \"\")" in body)
        assertFalse("delete(" in body)
    }

    @Test fun editingADueDateOnATaskKeepsItsNotifications() {
        val vm = src("ui/agenda/AgendaViewModel.kt")
        assertTrue(vm.split("DueAlerts.afterDueEdit").size - 1 >= 2) // saveDetails and setDue
    }

    @Test fun completedTasksAreNotScheduledAndCompletionCancelsAlarms() {
        val sched = src("reminders/ReminderScheduler.kt")
        assertTrue("entries.filterNot { it.done }" in sched)
        val repo = src("agenda/AgendaRepository.kt")
        assertTrue("reminderScheduler.cancelEntry(id)" in repo)
        assertTrue(Regex("""tmp\.renameTo\(localFile\)""").containsMatchIn(repo)) // atomic agenda write
    }

    @Test fun updateByTitleAsksWhichNoteInsteadOfEditingAnArbitraryOne() {
        val t = src("tools/ArchiveTools.kt")
        assertTrue("Quale intendi?" in t)
        assertTrue("\"append\"" in t)
    }

    @Test fun noRoomSchemaWasChangedByThisStep() {
        // Room version stays pinned by JarvisMigrationsTest; here we pin that no new entity was added to the DB class.
        val db = src("background/JarvisDatabase.kt")
        assertTrue("version = 15" in db)
        assertFalse("NoteEntity" in db)
    }
}
