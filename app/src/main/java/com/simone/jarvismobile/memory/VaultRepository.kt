package com.simone.jarvismobile.memory

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord
import com.simone.jarvismobile.core.memory.MemoryRecordCodec
import com.simone.jarvismobile.core.memory.MemoryStructure
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scoped access to the user's Obsidian vault via the Storage Access Framework
 * (docs/PRIVACY.md, docs/SECURITY.md): the user grants a folder with the system
 * picker, we take a persistable read/write grant, and only read `.md` files plus
 * write the dedicated `JARVIS/` Markdown files. No `MANAGE_EXTERNAL_STORAGE`,
 * no access outside that tree, and nothing leaves the device. The
 * vault stays the human-readable source of truth; the in-memory index is a
 * rebuildable cache ([MemoryIndex]).
 */
@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val memoryMutex = Mutex()

    /** A raw Markdown note read from the vault. */
    data class RawNote(val path: String, val content: String)
    data class SearchHit(val path: String, val excerpt: String)

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
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
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

    /** Controlled search: only the already-authorized vault tree is visible. */
    suspend fun searchNotes(query: String, limit: Int = 8): List<SearchHit> {
        val terms = query.lowercase().split(Regex("""[^\p{L}\p{Nd}]+"""))
            .filter { it.length >= 2 }
        if (terms.isEmpty()) return emptyList()
        return readAllNotes().asSequence()
            .mapNotNull { note ->
                val haystack = "${note.path}\n${note.content}".lowercase()
                val score = terms.count(haystack::contains)
                if (score == 0) null else Triple(score, note.path, excerpt(note.content, terms.first()))
            }
            .sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }.thenBy { it.second })
            .take(limit.coerceIn(1, 20))
            .map { SearchHit(it.second, it.third) }
            .toList()
    }

    private fun excerpt(content: String, needle: String): String {
        val clean = content.replace(Regex("""\s+"""), " ").trim()
        val index = clean.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
        val start = (index - 60).coerceAtLeast(0)
        return clean.substring(start, (start + 220).coerceAtMost(clean.length))
    }

    /**
     * Appends a timestamped line to `JARVIS/Memoria.md` inside the vault, creating
     * the folder/file if needed. Read-modify-write with truncate ("w") for maximum
     * provider compatibility (the memory file stays small). Returns false if there
     * is no vault or the provider is read-only.
     */
    suspend fun addMemory(text: String, requestedKind: MemoryKind? = null): MemoryRecord? =
        memoryMutex.withLock {
            val body = text.replace(Regex("""\s+"""), " ").trim()
            if (body.isBlank() || MemoryStructure.containsCredential(body)) return@withLock null
            if (!isConfigured()) return@withLock null
            val now = System.currentTimeMillis()
            val fields = MemoryStructure.extract(body)
            val record = MemoryRecord(
                id = UUID.randomUUID().toString(),
                text = body,
                kind = requestedKind ?: MemoryStructure.classify(body),
                createdAt = now,
                updatedAt = now,
                topics = fields.topics,
                people = fields.people,
                dates = fields.dates,
            )
            val records = readMemoryRecordsUnlocked()
            if (writeJarvisFile(MEMORY_FILE, MemoryRecordCodec.render(records + record))) record else null
        }

    suspend fun updateMemory(id: String, text: String, kind: MemoryKind): MemoryRecord? =
        memoryMutex.withLock {
            val body = text.replace(Regex("""\s+"""), " ").trim()
            if (body.isBlank() || MemoryStructure.containsCredential(body)) return@withLock null
            val records = readMemoryRecordsUnlocked()
            val current = records.firstOrNull { it.id == id } ?: return@withLock null
            val fields = MemoryStructure.extract(body)
            val updated = current.copy(
                text = body,
                kind = kind,
                updatedAt = System.currentTimeMillis(),
                topics = fields.topics,
                people = fields.people,
                dates = fields.dates,
            )
            val next = records.map { if (it.id == id) updated else it }
            if (writeJarvisFile(MEMORY_FILE, MemoryRecordCodec.render(next))) updated else null
        }

    suspend fun deleteMemory(id: String): MemoryRecord? = memoryMutex.withLock {
        val records = readMemoryRecordsUnlocked()
        val removed = records.firstOrNull { it.id == id } ?: return@withLock null
        if (writeJarvisFile(MEMORY_FILE, MemoryRecordCodec.render(records.filterNot { it.id == id }))) {
            removed
        } else {
            null
        }
    }

    suspend fun readMemoryRecords(): List<MemoryRecord> = memoryMutex.withLock {
        readMemoryRecordsUnlocked()
    }

    private suspend fun readMemoryRecordsUnlocked(): List<MemoryRecord> =
        MemoryRecordCodec.parse(readJarvisFile(MEMORY_FILE).orEmpty())

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
            MemoryRecordCodec.parse(text)
                .map { it.text }
                .takeLast(limit)
                .reversed()
        }.getOrDefault(emptyList())
    }

    /**
     * Reads a single file inside the `JARVIS/` folder of the vault, or null when
     * there is no vault / the file does not exist yet. Used by the agenda, which
     * needs the whole file (not just appended lines) to re-sort and rewrite it.
     */
    suspend fun readJarvisFile(fileName: String): String? = withContext(Dispatchers.IO) {
        val tree = tree() ?: return@withContext null
        runCatching {
            val folder = tree.findFile(MEMORY_DIR)?.takeIf { it.isDirectory } ?: return@withContext null
            val file = folder.findFile(fileName) ?: return@withContext null
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    /**
     * Writes (creating if needed) a file inside `JARVIS/`, replacing its contents.
     * Returns false when there is no vault or the provider refuses the write.
     */
    suspend fun writeJarvisFile(fileName: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val tree = tree() ?: return@withContext false
        runCatching {
            val folder = tree.findFile(MEMORY_DIR)?.takeIf { it.isDirectory }
                ?: tree.createDirectory(MEMORY_DIR)
                ?: return@withContext false
            val file = folder.findFile(fileName)
                ?: folder.createFile("text/markdown", fileName)
                ?: return@withContext false
            context.contentResolver.openOutputStream(file.uri, "w")?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }

    private suspend fun tree(): DocumentFile? {
        val uriStr = settings.vaultUri.first()
        if (uriStr.isBlank()) return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) }.getOrNull()
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
    }
}
