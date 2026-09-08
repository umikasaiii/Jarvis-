package com.simone.jarvismobile.corebridge

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Books the periodic queue-flush job on WorkManager — same
 * `enqueueUniquePeriodicWork(..., UPDATE, ...)` + `setBackoffCriteria`
 * pattern as [com.simone.jarvismobile.backup.BackupScheduler]. Not an exact
 * alarm: a queued event catching up a few minutes late is fine, and this
 * must never behave like the time-critical reminder/automation alarms.
 * [EventBridge.publish] already attempts an immediate flush when Core looks
 * online, so this periodic job exists only to retry what that immediate
 * attempt missed (Core came back online later, or the flush itself failed).
 */
@Singleton
class EventBridgeScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Re-points the periodic flush at the current settings, or cancels it
     * when Event Bridge/Core is off — or, per § JARVIS Implementation
     * Master Plan PASSAGGIO 11 §F1, when remote transport itself is
     * disabled: a periodic job whose only job is retrying a flush that
     * always no-ops (`EVENT_BRIDGE_REMOTE_TRANSPORT_ENABLED == false`,
     * since `jarvis-protocol` has no event-ingestion endpoint yet) would
     * just be resource use for a deferred architecture with no consumer.
     */
    suspend fun sync() {
        if (!EVENT_BRIDGE_REMOTE_TRANSPORT_ENABLED || !settings.eventBridgeEnabled.first() || !settings.coreEnabled.first()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<EventBridgeWorker>(FLUSH_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val WORK_NAME = "jarvis_event_bridge_flush"
        const val TAG = "jarvis_event_bridge"
        const val FLUSH_INTERVAL_MINUTES = 30L
    }
}
