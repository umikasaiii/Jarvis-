package com.simone.jarvismobile.core.backup

/** Where a backup is in its lifecycle. */
enum class BackupStatus { PENDING, RUNNING, COMPLETED, FAILED, UPLOADED }

/**
 * How an item is stored in a backup. [FILE] is copied into the (encrypted)
 * archive; [MANIFEST_ONLY] is a heavy, re-downloadable asset (AI models, offline
 * Wikipedia) that is NOT copied — only its name/version/path/hash are recorded so
 * a restore knows what to fetch again (spec: don't back up heavy re-downloadables).
 */
enum class EntryKind { FILE, MANIFEST_ONLY }

/**
 * One item in a backup manifest: a real file inside the archive, or a
 * manifest-only reference to a heavy asset. [sha256] lets a restore verify
 * integrity and lets the next backup skip unchanged files (incremental).
 */
data class BackupEntry(
    val name: String,
    val relPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val kind: EntryKind = EntryKind.FILE,
    /** For MANIFEST_ONLY: where to re-obtain it (a version tag or source path). */
    val sourceRef: String = "",
    /**
     * For an incremental backup: the id of the backup that actually holds this
     * file's bytes when it was unchanged in this run (empty = this backup).
     */
    val storedInBackupId: String = "",
)

/**
 * The JSON manifest written for every backup (spec): identity, timestamp,
 * version, size, per-entry hashes and status. It is the source of truth for
 * restore and for the incremental diff of the next run.
 */
data class BackupManifest(
    val id: String,
    val createdAt: Long,
    val schemaVersion: Int,
    val appVersion: String,
    val status: BackupStatus,
    val totalSizeBytes: Long,
    /** SHA-256 of the encrypted archive, filled once it is written. */
    val archiveSha256: String = "",
    val entries: List<BackupEntry> = emptyList(),
) {
    /** relPath → sha256 for the real files, used to diff against the next run. */
    fun fileHashes(): Map<String, String> =
        entries.filter { it.kind == EntryKind.FILE }.associate { it.relPath to it.sha256 }
}

/** Grandfather-father-son retention: how many daily/weekly/monthly to keep. */
data class RetentionPolicy(
    val daily: Int = 7,
    val weekly: Int = 4,
    val monthly: Int = 6,
)

/** A stored backup as retention sees it. */
data class BackupRef(val id: String, val createdAt: Long)
