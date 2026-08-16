package com.simone.jarvismobile.backup.drive

import android.util.Log
import com.simone.jarvismobile.backup.CloudBackupProvider
import com.simone.jarvismobile.backup.CloudBackupRef
import com.simone.jarvismobile.backup.CloudResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Google Drive backend for [CloudBackupProvider], built on
 * [GoogleAuthManager] (Identity/AuthorizationClient, `drive.file` scope) and
 * [GoogleDriveRestClient] (the REST calls themselves). Everything lives in
 * one "JARVIS Backups" folder this app creates in the user's own Drive (see
 * [GoogleDriveRestClient] — `drive.file` has no hidden appdata space). Each
 * backup is two remote files, `<id>.enc` and `<id>.manifest.json`; [list]
 * pairs them back up by name.
 */
@Singleton
class GoogleDriveBackupProvider @Inject constructor(
    private val auth: GoogleAuthManager,
    private val rest: GoogleDriveRestClient,
    private val credentials: DriveCredentialStore,
) : CloudBackupProvider {

    override val id = ID
    override val label = "Google Drive"

    override suspend fun isConfigured(): Boolean = auth.isConnected()

    override suspend fun upload(backupId: String, archive: File, manifest: File): CloudResult {
        val token = auth.currentAccessToken() ?: return CloudResult.Unavailable("Account Google non collegato")
        val folderId = resolveFolderId(token) ?: return CloudResult.Unavailable("Cartella di backup su Drive non disponibile")
        val archiveResult = uploadWithRetry(token, "$backupId$ARCHIVE_SUFFIX", archive, "application/octet-stream", folderId)
        val archiveFile = when (archiveResult) {
            is DriveResult.Ok -> archiveResult.value
            is DriveResult.Unauthorized -> return CloudResult.Unavailable("Sessione Google scaduta, riprova più tardi")
            is DriveResult.Error -> {
                // The cached folder id may be stale (e.g. the folder was
                // deleted in Drive) — drop it so the next attempt re-resolves
                // instead of failing the same way forever.
                credentials.backupFolderId = null
                return CloudResult.Failed(archiveResult.reason)
            }
        }
        // Post-upload hash check (spec): trust the cloud copy only once Drive's
        // own md5Checksum for what it just stored matches the local file.
        val localMd5 = rest.md5(archive)
        if (archiveFile.md5Checksum != null && !archiveFile.md5Checksum.equals(localMd5, ignoreCase = true)) {
            Log.w(TAG, "post_upload_hash_mismatch id=$backupId")
            runCatching { deleteRaw(token, archiveFile.id) }
            return CloudResult.Failed("Verifica hash post-upload non riuscita")
        }
        val manifestResult = uploadWithRetry(token, "$backupId$MANIFEST_SUFFIX", manifest, "application/json", folderId)
        if (manifestResult !is DriveResult.Ok) {
            // The archive alone is unusable without its manifest on restore —
            // clean it up rather than leave an orphaned half-upload behind.
            runCatching { deleteRaw(token, archiveFile.id) }
            return CloudResult.Failed("Caricamento del manifest non riuscito")
        }
        return CloudResult.Uploaded(archiveFile.id)
    }

    override suspend fun list(): List<CloudBackupRef> {
        val token = auth.currentAccessToken() ?: return emptyList()
        val folderId = resolveFolderId(token) ?: return emptyList()
        val files = when (val result = listWithRetry(token, folderId)) {
            is DriveResult.Ok -> result.value
            else -> return emptyList()
        }
        val archives = files.filter { it.name.endsWith(ARCHIVE_SUFFIX) }
        val manifestIdByBackupId = files
            .filter { it.name.endsWith(MANIFEST_SUFFIX) }
            .associate { it.name.removeSuffix(MANIFEST_SUFFIX) to it.id }
        return archives.map { archive ->
            val backupId = archive.name.removeSuffix(ARCHIVE_SUFFIX)
            CloudBackupRef(backupId, archive.id, manifestIdByBackupId[backupId], archive.sizeBytes)
        }
    }

    override suspend fun downloadArchive(ref: CloudBackupRef): ByteArray? = download(ref.archiveRemoteId)

    override suspend fun downloadManifest(ref: CloudBackupRef): ByteArray? =
        ref.manifestRemoteId?.let { download(it) }

    override suspend fun delete(ref: CloudBackupRef) {
        val token = auth.currentAccessToken() ?: return
        runCatching { deleteRaw(token, ref.archiveRemoteId) }
        ref.manifestRemoteId?.let { runCatching { deleteRaw(token, it) } }
    }

    private suspend fun download(fileId: String): ByteArray? {
        val token = auth.currentAccessToken() ?: return null
        return when (val result = downloadWithRetry(token, fileId)) {
            is DriveResult.Ok -> result.value
            else -> null
        }
    }

    /** The cached "JARVIS Backups" folder id, or a freshly found/created one. */
    private suspend fun resolveFolderId(token: String): String? {
        credentials.backupFolderId?.let { return it }
        val found = (rest.findBackupFolder(token) as? DriveResult.Ok)?.value
        val folder = found ?: (rest.createBackupFolder(token) as? DriveResult.Ok)?.value ?: return null
        credentials.backupFolderId = folder.id
        return folder.id
    }

    // --- one retry after a forced token refresh on a 401 ---------------------

    private suspend fun uploadWithRetry(
        token: String,
        name: String,
        file: File,
        mimeType: String,
        parentFolderId: String,
    ): DriveResult<DriveFile> {
        val first = rest.upload(token, name, file, mimeType, parentFolderId)
        if (first !is DriveResult.Unauthorized) return first
        val refreshed = auth.currentAccessToken() ?: return first
        return rest.upload(refreshed, name, file, mimeType, parentFolderId)
    }

    private suspend fun listWithRetry(token: String, parentFolderId: String): DriveResult<List<DriveFile>> {
        val first = rest.list(token, parentFolderId)
        if (first !is DriveResult.Unauthorized) return first
        val refreshed = auth.currentAccessToken() ?: return first
        return rest.list(refreshed, parentFolderId)
    }

    private suspend fun downloadWithRetry(token: String, fileId: String): DriveResult<ByteArray> {
        val first = rest.download(token, fileId)
        if (first !is DriveResult.Unauthorized) return first
        val refreshed = auth.currentAccessToken() ?: return first
        return rest.download(refreshed, fileId)
    }

    private suspend fun deleteRaw(token: String, fileId: String) {
        val first = rest.delete(token, fileId)
        if (first is DriveResult.Unauthorized) {
            val refreshed = auth.currentAccessToken() ?: return
            rest.delete(refreshed, fileId)
        }
    }

    companion object {
        const val ID = "gdrive"
        private const val ARCHIVE_SUFFIX = ".enc"
        private const val MANIFEST_SUFFIX = ".manifest.json"
        private const val TAG = "JarvisDrive"
    }
}
