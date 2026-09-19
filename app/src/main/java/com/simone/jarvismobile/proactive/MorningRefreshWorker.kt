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
 *
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE A (§10, P0) — DATA ONLY. This worker no longer composes,
 * notifies, or mutates morning dispatch ownership in any way: it refreshes
 * the underlying data caches (weather/agenda/Health where permission and
 * lifecycle allow) and nothing else. It MUST NOT compose a MORNING_DIGEST
 * suggestion, call the notifier, call `NotificationManager`, speak
 * the digest, or recreate a dismissed card — the one-shot contract (§9)
 * means no automatic path may ever re-open a day's briefing after dispatch
 * has entered its possibly-side-effecting boundary. Superseding the prior
 * "silent +10/+60 refresh closes duplication" conclusion, which this same
 * codebase already found to be an incomplete fix in practice — see
 * `docs/JARVIS_PROACTIVITY_RELIABILITY_CLOSURE_AUDIT.md` §2.
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
            // § WORK PACKAGE A §10 — data-only. No composition, no notifier
            // call, no dispatch-ownership mutation, no re-post, ever.
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
        fun morningTriggerScheduler(): MorningTriggerScheduler
    }

    companion object {
        const val KEY_DELAY_LABEL = "delay_label"
        const val KEY_REQUESTED_AT_MS = "requested_at_ms"
    }
}
