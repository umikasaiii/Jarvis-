package com.simone.jarvismobile.backup

import java.io.File

/** Outcome of a cloud upload. [Unavailable] means "try again later" (offline / not signed in). */
sealed interface CloudResult {
    data class Uploaded(val remoteId: String) : CloudResult
    data class Unavailable(val reason: String) : CloudResult
    data class Failed(val reason: String) : CloudResult
}

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

    companion object { const val ID = "none" }
}

/**
 * Google Drive provider. The Drive REST upload itself is a small, well-defined
 * call, but it needs an OAuth account connected through Google Sign-In, which
 * cannot be wired up or tested in this build environment (no Google services,
 * no signing config). Rather than ship a fake "success", this reports itself as
 * not configured, so the queue keeps the encrypted backup pending and the UI
 * shows an honest "da collegare" state until real sign-in lands.
 */
class GoogleDriveBackupProvider : CloudBackupProvider {
    override val id = ID
    override val label = "Google Drive"
    override suspend fun isConfigured() = false
    override suspend fun upload(backupId: String, archive: File, manifest: File) =
        CloudResult.Unavailable("Account Google non collegato")

    companion object { const val ID = "gdrive" }
}
