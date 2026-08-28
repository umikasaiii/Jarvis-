package com.simone.jarvismobile.proactive

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Books the periodic proactive check on WorkManager when proactivity is on, and
 * cancels it when off. A few checks a day are enough — the governor decides
 * whether any given run actually says anything.
 */
@Singleton
class ProactiveScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun sync() {
        if (!settings.proactiveEnabled.first()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        apply(true)
    }

    fun apply(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        // 1h, not 3h: this is the coarse fallback for whoever hasn't enabled
        // "Automazioni in background" (§ AutomationEventService) — without that
        // opt-in service there is no real unlock event to react to, so this
        // periodic tick is the only thing that can ever deliver the morning
        // digest. At 3h a real first unlock at, say, 06:05 could sit undelivered
        // until this run finally lands, up to just under 3 hours later — a real
        // bug the user hit ("il briefing mi è arrivato un'ora dopo lo sblocco").
        // Paired with the widened window in [ProactiveManager.candidatesFor]
        // (no longer confined to a 6-10 slice), this caps the worst case at ~1h
        // instead of ~3h55m.
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(1, TimeUnit.HOURS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "jarvis_proactive_check"
        const val TAG = "jarvis_proactive"
    }
}
