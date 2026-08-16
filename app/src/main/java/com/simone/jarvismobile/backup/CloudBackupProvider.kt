package com.simone.jarvismobile.backup

import java.io.File

/** Outcome of a cloud upload. [Unavailable] means "try again later" (offline / not signed in). */
sealed interface CloudResult {
    data class Uploaded(val remoteId: String) : CloudResult
    data class Unavailable(val reason: String) : CloudResult
    data class Failed(val reason: String) : CloudResult
}

/** One backup as a cloud provider lists it: its archive and (if present) manifest remote ids. */
data class CloudBackupRef(
    val backupId: String,
    val archiveRemoteId: String,
    val manifestRemoteId: String?,
    val sizeBytes: Long,
)

/**
 * A place an encrypted backup can be copied to (spec: Google Drive, OneDrive,
 * Dropbox, WebDAV, S3, NAS — added over time behind this one interface). The
 * cloud is only ever a *copy*: JARVIS is fully usable with no provider at all,
 * and the local backup is the source of truth.
 *
 * Implementations receive the already-encrypted archive; they must never see
 * plaintext and must never be required for the app to work offline.
 */
interface CloudBackupProvider {
    /** Stable id used in preferences and logs (e.g. "gdrive"). */
    val id: String

    /** Human-readable name for the UI (e.g. "Google Drive"). */
    val label: String

    /** Whether the user has connected/authorised this provider on this device. */
    suspend fun isConfigured(): Boolean

    /**
     * Uploads the encrypted [archive] for backup [backupId], plus its plaintext
     * [manifest] JSON. Returning [CloudResult.Unavailable] tells the queue to
     * retry later rather than dropping the backup.
     */
    suspend fun upload(backupId: String, archive: File, manifest: File): CloudResult

    /** Every backup this provider currently holds — used for retention pruning and restore-on-a-new-device. */
    suspend fun list(): List<CloudBackupRef>

    /** Raw bytes of [ref]'s encrypted archive, or null on any failure. */
    suspend fun downloadArchive(ref: CloudBackupRef): ByteArray?

    /** Raw bytes of [ref]'s plaintext manifest JSON, or null on any failure. */
    suspend fun downloadManifest(ref: CloudBackupRef): ByteArray?

    /** Removes [ref] from the cloud (retention pruning). Best-effort; never throws. */
    suspend fun delete(ref: CloudBackupRef)
}

/**
 * Default provider when the user has chosen no cloud: everything stays on the
 * device. Selecting it is a valid, fully-offline configuration.
 */
class NoCloudProvider : CloudBackupProvider {
    override val id = ID
    override val label = "Solo locale"
    override suspend fun isConfigured() = true
    override suspend fun upload(backupId: String, archive: File, manifest: File) =
        CloudResult.Unavailable("Nessun cloud selezionato")
    override suspend fun list() = emptyList<CloudBackupRef>()
    override suspend fun downloadArchive(ref: CloudBackupRef) = null
    override suspend fun downloadManifest(ref: CloudBackupRef) = null
    override suspend fun delete(ref: CloudBackupRef) = Unit

    companion object { const val ID = "none" }
}
