package com.simone.jarvismobile.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simone.jarvismobile.weather.receipt.ForecastDecisionReceiptRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Periodic proactive check. Running a few times a day is cheap because the
 * [ProactiveManager] does no work of its own beyond reading a few signals, and the
 * governor's budget / quiet hours / once-a-day dedup keep it from ever repeating.
 * Dependencies come through an entry point, matching the other workers here.
 *
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D.1 §3. Also prunes [ForecastDecisionReceiptRepository]'s bounded
 * local retention (90 days AND max 4096 rows) on this SAME hourly tick —
 * deliberately not a second scheduler: this worker already runs whenever
 * proactivity itself is enabled (`ProactiveScheduler.sync()`), which is a
 * tighter fit than [com.simone.jarvismobile.weather.WeatherScheduler]'s own
 * periodic tick (gated on `weatherEnabled` alone — a user can have weather
 * evaluations write receipts, via the evening `ProactiveManager.run()`
 * window, on days weather itself stays on but proactivity's own periodic
 * worker is what actually fires). A prune failure is independent of and
 * never blocks the proactive evaluation itself (two separate `runCatching`
 * boundaries, run in sequence — never allowed to interact).
 */
class ProactiveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, ProactiveEntryPoint::class.java)
        runCatching { deps.proactive().evaluate() }
        runCatching { deps.forecastDecisionReceipts().prune() }
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProactiveEntryPoint {
        fun proactive(): ProactiveManager
        fun forecastDecisionReceipts(): ForecastDecisionReceiptRepository
    }
}
