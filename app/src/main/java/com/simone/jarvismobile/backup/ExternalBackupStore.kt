package com.simone.jarvismobile.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.simone.jarvismobile.core.backup.BackupManifest
import com.simone.jarvismobile.core.backup.ManifestCodec
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors backups into a folder the user picked with the system file picker
 * (Storage Access Framework). This is the "destination" the user asks for: a
 * real, visible place — internal shared storage, an SD card, a USB drive — that
 * **survives an uninstall** (unlike the app-private copy) and doubles as the
 * restore source when it already holds backups.
 *
 * Layout inside the chosen tree:
 * ```
 * <folder>/JARVIS-Backups/<backup-id>/backup.enc
 * <folder>/JARVIS-Backups/<backup-id>/manifest.json
 * ```
 * The archive stays AES-256-GCM encrypted; only the small plaintext manifest is
 * copied alongside, exactly as in the internal store.
 */
@Singleton
class ExternalBackupStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    /** Persists the picked folder and takes a durable read+write grant. */
    suspend fun setFolder(treeUri: Uri) {
        // Release the previous grant, if any, before replacing it.
        val old = settings.backupFolderUri.first()
        if (old.isNotBlank() && old != treeUri.toString()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settings.setBackupFolderUri(treeUri.toString())
    }

    suspend fun clearFolder() {
        val old = settings.backupFolderUri.first()
        if (old.isNotBlank()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        settings.clearBackupFolderUri()
    }

    suspend fun isConfigured(): Boolean = settings.backupFolderUri.first().isNotBlank()

    /** Human-readable folder name for the UI, or null when none is chosen. */
    suspend fun folderName(): String? {
        val uri = settings.backupFolderUri.first().takeIf { it.isNotBlank() } ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull()
    }

    /** Copies one internal backup directory (enc + manifest) into the folder. */
    suspend fun mirror(backupDir: File): Boolean = withContext(Dispatchers.IO) {
        val root = backupsRoot() ?: return@withContext false
        val enc = File(backupDir, ENC)
        val manifest = File(backupDir, MANIFEST)
        if (!enc.exists() || !manifest.exists()) return@withContext false
        runCatching {
            val dir = root.findFile(backupDir.name)?.takeIf { it.isDirectory }
                ?: root.createDirectory(backupDir.name)
                ?: return@withContext false
            writeInto(dir, ENC, enc.readBytes())
            writeInto(dir, MANIFEST, manifest.readBytes())
            true
        }.getOrElse {
            Log.w(TAG, "mirror_failed ${it.javaClass.simpleName}")
            false
        }
    }

    /** Every manifest present in the folder, so restore can list external backups. */
    suspend fun listManifests(): List<BackupManifest> = withContext(Dispatchers.IO) {
        val root = backupsRoot(create = false) ?: return@withContext emptyList()
        root.listFiles().filter { it.isDirectory }.mapNotNull { dir ->
            val manifest = dir.findFile(MANIFEST) ?: return@mapNotNull null
            val text = readText(manifest) ?: return@mapNotNull null
            runCatching { ManifestCodec.decode(text) }.getOrNull()
        }
    }

    /**
     * Copies an external backup back into the internal [root] so the normal
     * restore/verify path can read its encrypted archive. No-op (true) when the
     * internal copy already exists.
     */
    suspend fun importInto(root: File, id: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(root, id)
        if (File(target, ENC).exists() && File(target, MANIFEST).exists()) return@withContext true
        val extRoot = backupsRoot(create = false) ?: return@withContext false
        val dir = extRoot.findFile(id)?.takeIf { it.isDirectory } ?: return@withContext false
        val enc = dir.findFile(ENC) ?: return@withContext false
        val manifest = dir.findFile(MANIFEST) ?: return@withContext false
        runCatching {
            target.mkdirs()
            readText(manifest)?.let { File(target, MANIFEST).writeText(it) } ?: return@withContext false
            context.contentResolver.openInputStream(enc.uri)?.use { input ->
                File(target, ENC).outputStream().use { input.copyTo(it) }
            } ?: return@withContext false
            true
        }.getOrElse {
            Log.w(TAG, "import_failed ${it.javaClass.simpleName}")
            false
        }
    }

    /** Deletes one backup folder by id (retention, or a user "Elimina"). */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val root = backupsRoot(create = false) ?: return@withContext
        root.findFile(id)?.takeIf { it.isDirectory }?.let { runCatching { it.delete() } }
        Unit
    }

    // --- helpers ------------------------------------------------------------

    private suspend fun backupsRoot(create: Boolean = true): DocumentFile? {
        val uri = settings.backupFolderUri.first().takeIf { it.isNotBlank() } ?: return null
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return null
        if (!tree.canRead()) return null
        val existing = tree.findFile(SUBDIR)?.takeIf { it.isDirectory }
        return existing ?: if (create) tree.createDirectory(SUBDIR) else null
    }

    private fun writeInto(dir: DocumentFile, name: String, bytes: ByteArray) {
        dir.findFile(name)?.delete()
        val file = dir.createFile(MIME, name) ?: return
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
    }

    private fun readText(file: DocumentFile): String? = runCatching {
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    private companion object {
        const val TAG = "JarvisBackupExt"
        const val SUBDIR = "JARVIS-Backups"
        const val ENC = "backup.enc"
        const val MANIFEST = "manifest.json"
        const val MIME = "application/octet-stream"
    }
}
