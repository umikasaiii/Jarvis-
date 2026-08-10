package com.simone.jarvismobile.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Periodic proactive check. Running a few times a day is cheap because the
 * [ProactiveManager] does no work of its own beyond reading a few signals, and the
 * governor's budget / quiet hours / once-a-day dedup keep it from ever repeating.
 * Dependencies come through an entry point, matching the other workers here.
 */
class ProactiveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, ProactiveEntryPoint::class.java)
        runCatching { deps.proactive().evaluate() }
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProactiveEntryPoint {
        fun proactive(): ProactiveManager
    }
}
