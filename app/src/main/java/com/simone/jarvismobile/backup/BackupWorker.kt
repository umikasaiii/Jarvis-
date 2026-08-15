package com.simone.jarvismobile.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.ui.MainActivity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * Runs the scheduled evening backup: make the local encrypted snapshot, verify
 * it, then hand it to the cloud queue (which only does anything if the user
 * connected a provider). A failed local backup retries with backoff and, on the
 * final give-up, posts one discrete notification — the only time the user is
 * bothered (spec). A cloud step that is merely deferred (offline / not signed
 * in) is not a failure: the encrypted backup stays queued for next time. An
 * optional place gate ("casa o casa Francy", § Impostazioni › Backup) can also
 * defer a run silently to the next cycle without ever counting as an error.
 *
 * Dependencies come through a Hilt entry point, matching the other workers here.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val deps = EntryPointAccessors.fromApplication(
        appContext.applicationContext,
        BackupEntryPoint::class.java,
    )

    override suspend fun doWork(): Result {
        // "Casa o casa Francy": an optional place gate on top of WorkManager's
        // own Wi-Fi/charging/battery Constraints. Empty selection (the default)
        // means unrestricted, so a user who never opens Luoghi sees no change.
        // Not being at a required place tonight is a deferral, not a failure —
        // WorkManager tries again on its next 24h cycle, silently.
        if (!runCatching { placeGateOk() }.getOrDefault(true)) {
            Log.i(TAG, "backup_skipped_not_at_place")
            return Result.success()
        }
        val repo = deps.backup()
        val manifest = runCatching { repo.runBackup() }.getOrNull()
        if (manifest == null) {
            return if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                notifyFailure("Il backup serale non è riuscito. Riprova da Impostazioni › Backup.")
                Result.failure()
            }
        }
        // Local backup verified against its own hash before we trust it.
        val ok = runCatching { repo.verify(manifest.id) }.getOrDefault(false)
        if (!ok) {
            notifyFailure("Il backup è stato creato ma non ha superato la verifica di integrità.")
            return Result.failure()
        }
        // Cloud is optional and best-effort: queue, then try. Never fails the job.
        runCatching {
            deps.cloud().enqueue(manifest.id)
            deps.cloud().processQueue()
        }
        return Result.success()
    }

    /**
     * True when no place is required, or the fused place the [ContextEngine]
     * currently believes we are at is one of the required ones. A gate that
     * cannot be evaluated (an internal error) fails **open** — a backup taken
     * outside the chosen place is a minor inconvenience; one silently skipped
     * forever because of a bug is the user's safety net quietly disappearing.
     */
    private suspend fun placeGateOk(): Boolean {
        val required = deps.settings().backupPlaceIds.first()
        if (required.isEmpty()) return true
        val current = deps.contextEngine().state.value.placeId
        return current != null && current in required
    }

    private fun notifyFailure(text: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The low-importance system channel keeps this discrete (no sound/peek).
        val notification = JarvisNotifications.styled(
            context = applicationContext,
            channelId = JarvisNotifications.CHANNEL_PROCESSING,
            title = "Backup JARVIS",
            text = text,
            contentIntent = open,
            expandableText = text,
        )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackupEntryPoint {
        fun backup(): BackupRepository
        fun cloud(): CloudSyncManager
        fun settings(): SettingsRepository
        fun contextEngine(): ContextEngine
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val NOTIFICATION_ID = 7_100
        private const val TAG = "JarvisBackup"
    }
}
