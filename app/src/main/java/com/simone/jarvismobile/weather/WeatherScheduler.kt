package com.simone.jarvismobile.weather

import android.content.Context
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
 * Books (or cancels) the periodic weather refresh, mirroring
 * [com.simone.jarvismobile.proactive.ProactiveScheduler]. A network
 * [Constraints] means a run with no connectivity simply defers rather than
 * failing — no retry storm, no error the user would ever see.
 */
@Singleton
class WeatherScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun sync() {
        if (!settings.weatherEnabled.first()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<WeatherRefreshWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "jarvis_weather_refresh"
        const val TAG = "jarvis_weather"
    }
}
