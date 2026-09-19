package com.simone.jarvismobile.proactive

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §3.
 *
 * **DISPOSITION: NO LONGER A SCHEDULING AUTHORITY.** This class used to own
 * NEXT_ALARM/CONFIGURED_TIME planning (FASE 2A.8/MICRO-PATCH 14.2.3) —
 * that entire responsibility has been absorbed into
 * [ProactiveScheduler] (§3, option A: "absorb its logic into
 * ProactiveScheduler and remove it"), the single canonical temporal owner.
 * This class MUST NOT — and no longer does — read settings independently,
 * compute fire times independently, create WorkManager/AlarmManager work
 * independently, own persisted schedule state, or make business eligibility
 * decisions. No two active scheduling owners.
 *
 * What remains is the one thing this class did that was never actually
 * about WHEN the morning digest fires: the POST-BRIEFING DATA REFRESH
 * (§ FASE 2A.8 RELEASE GATE G) scheduled only AFTER
 * [ProactiveManager] has already delivered today's digest through the
 * single dispatch owner ([ProactiveDeliveryDispatcher]) — a data-only
 * WorkManager job with no bearing on occurrence ownership.
 */
@Singleton
class MorningTriggerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * § FASE 2A.8 RELEASE GATE G — POST-BRIEFING MORNING REFRESH. Called only
     * by [ProactiveManager] right after it REALLY delivered a MORNING_DIGEST
     * (never on a Skip, never for EVENING_DIGEST). Enqueues two unique
     * one-time [MorningRefreshWorker] runs (+10min main attempt, catching a
     * Huawei Health→Health Connect sync that landed just after the digest
     * itself; +60min safety retry) — `ExistingWorkPolicy.KEEP` so a duplicate
     * call the same morning never double-books. WorkManager, not
     * [com.simone.jarvismobile.alarms.ExactAlarms]: unlike the briefing
     * itself, a refresh a few minutes late to Doze is an acceptable
     * degradation, not a broken promise.
     */
    fun schedulePostBriefingRefreshes() {
        val workManager = WorkManager.getInstance(context)
        val requestedAtMs = System.currentTimeMillis()
        val today = LocalDate.now()
        listOf(10L to "+10min", 60L to "+60min").forEach { (delayMinutes, label) ->
            val data = Data.Builder()
                .putString(MorningRefreshWorker.KEY_DELAY_LABEL, label)
                .putLong(MorningRefreshWorker.KEY_REQUESTED_AT_MS, requestedAtMs)
                .build()
            val request = OneTimeWorkRequestBuilder<MorningRefreshWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(data)
                .addTag(TAG_POST_BRIEFING_REFRESH)
                .build()
            workManager.enqueueUniqueWork("${WORK_NAME_PREFIX}${label}_$today", ExistingWorkPolicy.KEEP, request)
        }
    }

    /** One entry per attempted post-briefing refresh (§F/§G diagnostics) — never the notification text itself, only timing/outcome. */
    data class PostBriefingRefreshDiagnostic(
        val delayLabel: String,
        val requestedAtMs: Long,
        val actualRunAtMs: Long,
        val outcome: String,
    )

    private val _lastPostBriefingRefreshes = MutableStateFlow<List<PostBriefingRefreshDiagnostic>>(emptyList())
    val lastPostBriefingRefreshes: StateFlow<List<PostBriefingRefreshDiagnostic>> = _lastPostBriefingRefreshes.asStateFlow()

    /** Called by [MorningRefreshWorker] itself once it has actually run. */
    fun recordPostBriefingRefresh(delayLabel: String, requestedAtMs: Long, actualRunAtMs: Long, outcome: String) {
        _lastPostBriefingRefreshes.value =
            (_lastPostBriefingRefreshes.value + PostBriefingRefreshDiagnostic(delayLabel, requestedAtMs, actualRunAtMs, outcome))
                .takeLast(10)
    }

    companion object {
        private const val WORK_NAME_PREFIX = "jarvis_morning_refresh_"
        private const val TAG_POST_BRIEFING_REFRESH = "jarvis_morning_refresh"
    }
}
