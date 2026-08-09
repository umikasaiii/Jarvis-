package com.simone.jarvismobile.backup

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.backup.BackupStatus
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
) {
    private val providers: Map<String, CloudBackupProvider> = listOf(
        NoCloudProvider(),
        GoogleDriveBackupProvider(),
    ).associateBy { it.id }

    private val root: File get() = File(context.filesDir, "backups").apply { mkdirs() }
    private val queueFile: File get() = File(root, QUEUE)

    val availableProviders: List<CloudBackupProvider> get() = providers.values.toList()

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
        if (!settings.backupCloudEnabled.first()) return@withContext true
        val provider = providers[settings.backupCloudProvider.first()] ?: return@withContext true
        if (provider.id == NoCloudProvider.ID) return@withContext true
        if (!provider.isConfigured()) {
            Log.i(TAG, "cloud_skip provider=${provider.id} not_configured")
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
                is CloudResult.Unavailable -> { remaining += id; allOk = false }
                is CloudResult.Failed -> { remaining += id; allOk = false; Log.w(TAG, "cloud_failed id=$id") }
            }
        }
        writeQueue(remaining)
        allOk && remaining.isEmpty()
    }

    fun pendingCount(): Int = readQueue().size

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
