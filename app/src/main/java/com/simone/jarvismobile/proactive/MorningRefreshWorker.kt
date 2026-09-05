package com.simone.jarvismobile.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.health.HealthConnectManager
import com.simone.jarvismobile.weather.WeatherManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * § FASE 2A.8 RELEASE GATE G — POST-BRIEFING MORNING REFRESH. Scheduled twice
 * by [MorningTriggerScheduler.schedulePostBriefingRefreshes] right after a
 * morning digest is REALLY delivered: +10min (the main attempt — catches a
 * Huawei Health→Health Connect sync that lands a few minutes after the
 * digest itself) and +60min (a safety retry). Reuses the exact same
 * repositories the digest itself and the rest of the app already use — no
 * second Health/Weather/Agenda pipeline.
 *
 * Degrades honestly rather than guessing: [HealthConnectManager.hasBackgroundPermission]
 * is checked before a background Health Connect read is attempted at all —
 * WorkManager runs with no foreground component, so without that permission
 * a read could silently fail or throw depending on the OEM; skipping it here
 * (while still refreshing weather/agenda, which need no such permission) is
 * the honest degradation the spec asks for, not a guess.
 */
class MorningRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, MorningRefreshEntryPoint::class.java)
        val delayLabel = inputData.getString(KEY_DELAY_LABEL) ?: "unknown"
        val requestedAtMs = inputData.getLong(KEY_REQUESTED_AT_MS, 0L)
        val actualRunAtMs = System.currentTimeMillis()

        val outcome = runCatching {
            val health = deps.health()
            runCatching { deps.agenda().reload() }
            runCatching { deps.weather().refresh() }
            val healthRefreshed = if (health.hasBackgroundPermission()) {
                runCatching { health.refresh() }.isSuccess
            } else {
                false
            }
            // Re-composes and re-posts the SAME morning-digest notification
            // (same id, replaces in place) with whatever is fresh now — never
            // re-speaks it (§ deliberate: a second spoken briefing 10-60min
            // later would be intrusive, not helpful).
            deps.proactiveManager().refreshMorningDigestNotification()
            if (healthRefreshed) "refreshed" else "refreshed_no_health_background_permission"
        }.getOrElse { e -> "failed:${e.javaClass.simpleName}" }

        deps.morningTriggerScheduler().recordPostBriefingRefresh(delayLabel, requestedAtMs, actualRunAtMs, outcome)
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MorningRefreshEntryPoint {
        fun health(): HealthConnectManager
        fun weather(): WeatherManager
        fun agenda(): AgendaRepository
        fun proactiveManager(): ProactiveManager
        fun morningTriggerScheduler(): MorningTriggerScheduler
    }

    companion object {
        const val KEY_DELAY_LABEL = "delay_label"
        const val KEY_REQUESTED_AT_MS = "requested_at_ms"
    }
}
