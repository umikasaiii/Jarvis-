package com.simone.jarvismobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BETA 1/8 — data-protection gap found by reading `collectSources()`: the app-private files that hold
 * the user's own Memoria, local Agenda/Automazioni, chat and short memory were not in any backup.
 * This pins (a) that they are now collected and (b) that the literal names in [BackupRepository] still
 * equal the constants owned by the repositories that write them (a rename there would silently drop
 * the file from backups again). Plain source scan: no Android runtime needed.
 */
class BackupCoversPrivateUserFilesTest {

    private fun src(rel: String): String {
        val p = "src/main/java/com/simone/jarvismobile/$rel"
        return (File(p).takeIf { it.isFile } ?: File("app/$p").takeIf { it.isFile }
            ?: error("cannot locate $p from ${File(".").absolutePath}")).readText()
    }

    private fun const(rel: String, name: String): String =
        Regex("""const val $name\s*=\s*"([^"]+)"""").find(src(rel))?.groupValues?.get(1)
            ?: error("const $name not found in $rel")

    @Test fun backupListsExactlyTheFilesItsOwnersWrite() {
        val owners = setOf(
            const("memory/VaultRepository.kt", "LOCAL_MEMORY_FILE"),
            const("agenda/AgendaRepository.kt", "LOCAL_FILE"),
            const("automation/AutomationRepository.kt", "LOCAL_FILE"),
            const("data/ChatStore.kt", "FILE"),
            const("memory/ConversationMemoryStore.kt", "FILE_NAME"),
        )
        assertEquals(owners, BackupPrivateFiles.FILES.toSet())
        assertEquals(const("memory/NoteBackgroundStore.kt", "DIR_NAME"), BackupPrivateFiles.BACKGROUNDS_DIR)
    }

    @Test fun collectSourcesActuallyUsesThem() {
        val body = src("backup/BackupRepository.kt")
        assertTrue(body.contains("for (name in BackupPrivateFiles.FILES)"))
        assertTrue(body.contains("BackupPrivateFiles.BACKGROUNDS_DIR"))
    }

    @Test fun everyEntryIsAPathSafeRelPath() {
        (BackupPrivateFiles.FILES + BackupPrivateFiles.BACKGROUNDS_DIR + "note_backgrounds/x.png").forEach {
            assertTrue(it, com.simone.jarvismobile.core.backup.BackupPathSafety.isSafeRelPath(it))
        }
    }
}
