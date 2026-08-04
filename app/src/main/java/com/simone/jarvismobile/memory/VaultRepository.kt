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

    /** Persists the picked vault folder and takes a durable read permission. */
    suspend fun setVault(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
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
}
