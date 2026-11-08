package com.simone.jarvismobile.backup

/**
 * BETA 1/8 — app-private files that hold the user's own data and must be part of every backup
 * (they live under `filesDir`, outside Room and outside the optional Obsidian vault).
 * Pinned to the owning classes' own constants by `BackupCoversPrivateUserFilesTest`:
 * `VaultRepository.LOCAL_MEMORY_FILE`, `AgendaRepository.LOCAL_FILE`, `AutomationRepository.LOCAL_FILE`,
 * `ChatStore.FILE`, `ConversationMemoryStore.FILE_NAME`, `NoteBackgroundStore.DIR_NAME`.
 */
internal object BackupPrivateFiles {
    val FILES = listOf("memoria.md", "agenda.md", "automazioni.md", "chat.json", "conversation_memory_v2.json")
    const val BACKGROUNDS_DIR = "note_backgrounds"
}
