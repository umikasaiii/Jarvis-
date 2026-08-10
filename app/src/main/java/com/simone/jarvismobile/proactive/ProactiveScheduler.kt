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
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(3, TimeUnit.HOURS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "jarvis_proactive_check"
        const val TAG = "jarvis_proactive"
    }
}
