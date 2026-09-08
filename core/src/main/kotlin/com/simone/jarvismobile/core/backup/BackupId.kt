package com.simone.jarvismobile.core.backup

/**
 * Validates a backup id's shape before it is ever used to build a filesystem
 * path or a `DocumentFile`/cloud lookup key (§ JARVIS Implementation Master
 * Plan PASSAGGIO 10 §F). Every id JARVIS itself ever produces matches
 * `backup-yyyyMMdd-HHmmss` (see `BackupRepository.runBackup()`); a manifest's
 * `id` field is otherwise plain, attacker-reachable JSON text — mirrored to
 * an external SAF folder or a cloud provider the user chose, either of which
 * could in principle carry a tampered `manifest.json` without needing the
 * archive's encryption key at all (the archive itself stays AES-GCM
 * authenticated; the manifest does not). Rejecting anything that is not
 * exactly this shape, by allow-list rather than by blocking `..`, is the
 * standard defense against a forged id turning into `File(root, id)`
 * escaping the backups directory (path traversal) or colliding with an
 * unrelated file.
 */
object BackupId {
    private val PATTERN = Regex("^backup-\\d{8}-\\d{6}$")

    fun isValid(id: String): Boolean = PATTERN.matches(id)
}
