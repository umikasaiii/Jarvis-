package com.simone.jarvismobile.backup

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.backup.drive.GoogleDriveBackupProvider
import com.simone.jarvismobile.core.backup.BackupManifest
import com.simone.jarvismobile.core.backup.BackupRef
import com.simone.jarvismobile.core.backup.BackupStatus
import com.simone.jarvismobile.core.backup.ManifestCodec
import com.simone.jarvismobile.core.backup.Retention
import com.simone.jarvismobile.core.backup.RetentionPolicy
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies encrypted backups to the selected cloud provider, off the local-backup
 * path (spec: cloud is a later copy, never the source of truth). Backups pending
 * upload are persisted to a plain-text queue file so an upload deferred while
 * offline survives a reboot and is retried on the next run. Nothing is uploaded
 * unless the user turned the cloud on and connected a provider.
 */
@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    googleDrive: GoogleDriveBackupProvider,
) {
    private val providers: Map<String, CloudBackupProvider> = listOf(
        NoCloudProvider(),
        googleDrive,
    ).associateBy { it.id }

    private val root: File get() = File(context.filesDir, "backups").apply { mkdirs() }
    private val queueFile: File get() = File(root, QUEUE)

    val availableProviders: List<CloudBackupProvider> get() = providers.values.toList()

    /**
     * The reason the most recent [processQueue] call didn't fully succeed, for a
     * caller (like the manual "Esegui backup ora" button) that wants to tell the
     * user *why* rather than just that it happened — null when cloud sync is off,
     * not configured, or the last run uploaded everything.
     */
    var lastFailureReason: String? = null
        private set

    /** Marks a freshly-made backup as needing upload (deduplicated). */
    fun enqueue(backupId: String) {
        val ids = readQueue()
        if (backupId !in ids) writeQueue(ids + backupId)
    }

    /**
     * Attempts to upload every queued backup with the selected provider. Ones the
     * provider can't take yet (offline / not signed in) stay queued for next time;
     * uploaded ones are removed and their manifest is marked UPLOADED.
     */
    suspend fun processQueue(): Boolean = withContext(Dispatchers.IO) {
        lastFailureReason = null
        if (!settings.backupCloudEnabled.first()) return@withContext true
        val provider = providers[settings.backupCloudProvider.first()] ?: return@withContext true
        if (provider.id == NoCloudProvider.ID) return@withContext true
        if (!provider.isConfigured()) {
            Log.i(TAG, "cloud_skip provider=${provider.id} not_configured")
            lastFailureReason = "Account ${provider.label} non collegato"
            return@withContext false
        }
        val remaining = ArrayList<String>()
        var allOk = true
        for (id in readQueue()) {
            val dir = File(root, id)
            val archive = File(dir, "backup.enc")
            val manifest = File(dir, "manifest.json")
            if (!archive.exists() || !manifest.exists()) continue // pruned meanwhile
            when (val res = provider.upload(id, archive, manifest)) {
                is CloudResult.Uploaded -> {
                    markUploaded(manifest)
                    Log.i(TAG, "cloud_uploaded id=$id remote=${res.remoteId}")
                }
                is CloudResult.Unavailable -> { remaining += id; allOk = false; lastFailureReason = res.reason }
                is CloudResult.Failed -> { remaining += id; allOk = false; lastFailureReason = res.reason; Log.w(TAG, "cloud_failed id=$id") }
            }
        }
        writeQueue(remaining)
        allOk && remaining.isEmpty()
    }

    fun pendingCount(): Int = readQueue().size

    /** Every manifest currently on the selected cloud provider, for restore's backup list. */
    suspend fun listManifests(): List<BackupManifest> = withContext(Dispatchers.IO) {
        val provider = activeProvider() ?: return@withContext emptyList()
        val refs = runCatching { provider.list() }.getOrDefault(emptyList())
        refs.mapNotNull { ref ->
            val bytes = runCatching { provider.downloadManifest(ref) }.getOrNull() ?: return@mapNotNull null
            runCatching { ManifestCodec.decode(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        }
    }

    /** Pulls backup [id]'s archive+manifest from the cloud into internal [root], if it lives there. */
    suspend fun importInto(root: File, id: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(root, id)
        if (File(target, "backup.enc").exists() && File(target, "manifest.json").exists()) return@withContext true
        val provider = activeProvider() ?: return@withContext false
        val refs = runCatching { provider.list() }.getOrDefault(emptyList())
        val ref = refs.firstOrNull { it.backupId == id } ?: return@withContext false
        val archiveBytes = provider.downloadArchive(ref) ?: return@withContext false
        val manifestBytes = provider.downloadManifest(ref) ?: return@withContext false
        runCatching {
            target.mkdirs()
            File(target, "backup.enc").writeBytes(archiveBytes)
            File(target, "manifest.json").writeBytes(manifestBytes)
            true
        }.getOrDefault(false)
    }

    /** Deletes backup [id] from the cloud (a user "Elimina", outside retention). */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val provider = activeProvider() ?: return@withContext
        val refs = runCatching { provider.list() }.getOrDefault(emptyList())
        val ref = refs.firstOrNull { it.backupId == id } ?: return@withContext
        runCatching { provider.delete(ref) }
    }

    /**
     * Grandfather-father-son retention over the cloud provider's own contents
     * (spec), mirroring [com.simone.jarvismobile.backup.BackupRepository]'s
     * local/external pruning. A backup whose id does not parse as the expected
     * `backup-yyyyMMdd-HHmmss` timestamp is left alone rather than guessed at —
     * every id here is JARVIS's own, so this should never happen, but an
     * unreadable date must never turn into a deletion.
     */
    suspend fun pruneRemote() = withContext(Dispatchers.IO) {
        val provider = activeProvider() ?: return@withContext
        val policy = RetentionPolicy(
            daily = settings.backupRetentionDaily.first(),
            weekly = settings.backupRetentionWeekly.first(),
            monthly = settings.backupRetentionMonthly.first(),
        )
        val remote = runCatching { provider.list() }.getOrDefault(emptyList())
        val refs = remote.mapNotNull { r -> parseBackupIdTimestamp(r.backupId)?.let { BackupRef(r.backupId, it) } }
        val toDelete = Retention.prune(refs, policy).toSet()
        remote.filter { it.backupId in toDelete }.forEach { runCatching { provider.delete(it) } }
    }

    /** The selected cloud provider, or null when cloud is off, unset, "no cloud" or not yet connected. */
    private suspend fun activeProvider(): CloudBackupProvider? {
        if (!settings.backupCloudEnabled.first()) return null
        val provider = providers[settings.backupCloudProvider.first()] ?: return null
        if (provider.id == NoCloudProvider.ID) return null
        if (!provider.isConfigured()) return null
        return provider
    }

    private fun parseBackupIdTimestamp(id: String): Long? = runCatching {
        SimpleDateFormat("'backup-'yyyyMMdd-HHmmss", Locale.US).parse(id)?.time
    }.getOrNull()

    private fun markUploaded(manifestFile: File) {
        runCatching {
            val manifest = ManifestCodec.decode(manifestFile.readText())
            manifestFile.writeText(ManifestCodec.encode(manifest.copy(status = BackupStatus.UPLOADED)))
        }
    }

    private fun readQueue(): List<String> = runCatching {
        if (!queueFile.exists()) emptyList()
        else queueFile.readText().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun writeQueue(ids: List<String>) {
        runCatching { queueFile.writeText(ids.distinct().joinToString("\n")) }
    }

    private companion object {
        const val TAG = "JarvisCloudSync"
        const val QUEUE = "upload_queue.txt"
    }
}
