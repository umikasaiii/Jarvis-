package com.simone.jarvismobile.weather

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Periodic weather refresh. [WeatherManager] itself checks the opt-in and does
 * nothing when it is off, so this worker can simply be scheduled whenever the
 * app starts and stay a harmless no-op for anyone who never turns weather on.
 */
class WeatherRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, WeatherEntryPoint::class.java)
        runCatching { deps.weather().refresh() }
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WeatherEntryPoint {
        fun weather(): WeatherManager
    }
}
