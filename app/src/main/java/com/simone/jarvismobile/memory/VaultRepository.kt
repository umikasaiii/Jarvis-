package com.simone.jarvismobile.memory

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only access to the user's Obsidian vault via the Storage Access Framework
 * (docs/PRIVACY.md, docs/SECURITY.md): the user grants a folder with the system
 * picker, we take a *persistable read* permission, and only ever read `.md` files
 * from it. No `MANAGE_EXTERNAL_STORAGE`, no writes, nothing leaves the device. The
 * vault stays the human-readable source of truth; the in-memory index is a
 * rebuildable cache ([MemoryIndex]).
 */
@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    /** A raw Markdown note read from the vault. */
    data class RawNote(val path: String, val content: String)

    /**
     * Persists the picked vault folder and takes a durable read+write permission
     * (write is needed so JARVIS can save "ricorda …" notes back into the vault;
     * on a read-only provider the write simply fails gracefully later).
     */
    suspend fun setVault(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settings.setVaultUri(treeUri.toString())
    }

    suspend fun clearVault() {
        val uriStr = settings.vaultUri.first()
        if (uriStr.isNotBlank()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriStr),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        settings.clearVaultUri()
    }

    suspend fun isConfigured(): Boolean = settings.vaultUri.first().isNotBlank()

    /** Display name of the selected vault folder, for the UI. */
    suspend fun vaultName(): String? {
        val uriStr = settings.vaultUri.first()
        if (uriStr.isBlank()) return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriStr))?.name }.getOrNull()
    }

    /**
     * Reads every `.md` file under the vault (recursively). Paths are vault-relative
     * (folder/sub/note.md). Unreadable files are skipped rather than failing the lot.
     */
    suspend fun readAllNotes(): List<RawNote> = withContext(Dispatchers.IO) {
        val uriStr = settings.vaultUri.first()
        if (uriStr.isBlank()) return@withContext emptyList()
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return@withContext emptyList()
        val out = ArrayList<RawNote>()
        collect(tree, "", out)
        out
    }

    /**
     * Appends a timestamped line to `JARVIS/Memoria.md` inside the vault, creating
     * the folder/file if needed. Read-modify-write with truncate ("w") for maximum
     * provider compatibility (the memory file stays small). Returns false if there
     * is no vault or the provider is read-only.
     */
    suspend fun appendMemory(text: String): Boolean = withContext(Dispatchers.IO) {
        val body = text.trim()
        if (body.isEmpty()) return@withContext false
        val uriStr = settings.vaultUri.first()
        if (uriStr.isBlank()) return@withContext false
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return@withContext false
        runCatching {
            val folder = tree.findFile(MEMORY_DIR)?.takeIf { it.isDirectory }
                ?: tree.createDirectory(MEMORY_DIR)
                ?: return@withContext false
            val file = folder.findFile(MEMORY_FILE)
                ?: folder.createFile("text/markdown", MEMORY_FILE)
                ?: return@withContext false
            val existing = context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val base = when {
                existing.isBlank() -> "# Memoria di JARVIS\n\n"
                existing.endsWith("\n") -> existing
                else -> "$existing\n"
            }
            val stamp = LocalDateTime.now().format(TS)
            val out = base + "- [$stamp] $body\n"
            context.contentResolver.openOutputStream(file.uri, "w")?.use {
                it.write(out.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }

    /**
     * Reads back the saved "ricorda …" lines from `JARVIS/Memoria.md`, newest
     * first. Used to answer "cosa devo fare?" from the file itself rather than
     * from the model, so recall can never be invented.
     */
    suspend fun listMemories(limit: Int = 10): List<String> = withContext(Dispatchers.IO) {
        val uriStr = settings.vaultUri.first()
        if (uriStr.isBlank()) return@withContext emptyList()
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return@withContext emptyList()
        runCatching {
            val folder = tree.findFile(MEMORY_DIR)?.takeIf { it.isDirectory } ?: return@withContext emptyList()
            val file = folder.findFile(MEMORY_FILE) ?: return@withContext emptyList()
            val text = context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()?.use { it.readText() } ?: return@withContext emptyList()
            text.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("- ") }
                .map { it.removePrefix("- ").trim() }
                .filter { it.isNotEmpty() }
                .toList()
                .takeLast(limit)
                .reversed()
        }.getOrDefault(emptyList())
    }

    private fun collect(dir: DocumentFile, prefix: String, out: MutableList<RawNote>) {
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory) {
                collect(f, "$prefix$name/", out)
            } else if (name.endsWith(".md", ignoreCase = true)) {
                val content = runCatching {
                    context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull() ?: continue
                out += RawNote("$prefix$name", content)
            }
        }
    }

    private companion object {
        const val MEMORY_DIR = "JARVIS"
        const val MEMORY_FILE = "Memoria.md"
        val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
