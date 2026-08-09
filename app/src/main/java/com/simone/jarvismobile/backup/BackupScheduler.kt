package com.simone.jarvismobile.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Books the nightly backup on WorkManager. A daily [PeriodicWorkRequest] with an
 * initial delay to the next configured time is used rather than an exact alarm:
 * a backup should wait for the user's own conditions (Wi-Fi, charging, battery),
 * which WorkManager [Constraints] express directly, and being a few minutes late
 * at night is fine. Reschedule whenever the time or constraints change; cancel
 * when auto-backup is turned off.
 */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    /** Re-points the schedule at the current settings, or cancels it when off. */
    suspend fun sync(now: LocalDateTime = LocalDateTime.now()) {
        if (!settings.backupEnabled.first()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val target = LocalTime.of(settings.backupHour.first(), settings.backupMinute.first())
        val delay = initialDelay(now, target)

        val wifiOnly = settings.backupWifiOnly.first()
        val chargingOnly = settings.backupChargingOnly.first()
        val minBattery = settings.backupMinBattery.first()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .setRequiresCharging(chargingOnly)
            .setRequiresBatteryNotLow(minBattery > 0)
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay.toMinutes().coerceAtLeast(1), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        // UPDATE keeps the existing periodicity but applies new timing/constraints.
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Runs a backup right now (the "Esegui backup ora" button), ignoring constraints. */
    fun runNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** Duration from [now] to the next occurrence of [target] time of day. */
    private fun initialDelay(now: LocalDateTime, target: LocalTime): Duration {
        var next = now.toLocalDate().atTime(target)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    private companion object {
        const val WORK_NAME = "jarvis_evening_backup"
        const val MANUAL_WORK = "jarvis_backup_now"
        const val TAG = "jarvis_backup"
    }
}
