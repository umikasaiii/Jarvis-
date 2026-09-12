package com.simone.jarvismobile.proactive

import com.simone.jarvismobile.core.proactive.OccurrenceClaimOutcome
import com.simone.jarvismobile.core.proactive.ProactiveOccurrenceReconciler
import com.simone.jarvismobile.core.proactive.ProactiveOccurrenceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.1 §E/§F/§G. The ONE
 * canonical owner of Morning Brief occurrence lifecycle — the durable half
 * mirroring [com.simone.jarvismobile.automation.rule.AutomationExecutor]'s
 * own two-layer defense (§G: "Use existing transactional/unique-key
 * facilities"): an in-process [Mutex] (mirrors `AutomationExecutor.fireMutex`,
 * the fast path for two triggers racing inside the SAME process) backed by
 * a Room row whose PRIMARY KEY + conditional UPDATE give a genuine
 * cross-process atomic claim (mirrors `AutomationExecutionDao`'s durable
 * dedup query, but here as an actual CLAIM before generation rather than a
 * post-hoc log lookup — §L: "claim the logical occurrence BEFORE expensive
 * generation").
 *
 * Multiple trigger sources (NEXT_ALARM, CONFIGURED_TIME, FIRST_UNLOCK,
 * PERIODIC_FALLBACK) all call [claim] for the SAME occurrence key on the
 * same morning — only one is ever told [OccurrenceClaimOutcome.Claimed]/
 * [OccurrenceClaimOutcome.TakeoverAllowed]; every other caller sees
 * [OccurrenceClaimOutcome.AlreadyOwned] and must perform no generation, no
 * delivery, no side effect (§G/§P).
 */
@Singleton
class ProactiveOccurrenceStore @Inject constructor(
    private val dao: ProactiveOccurrenceDao,
) {
    private val mutex = Mutex()

    /** § §O — bounded, privacy-safe evidence of the most recent claim decision (never the briefing text). */
    data class ClaimDiagnostic(
        val occurrenceKey: String,
        val logicalDate: String,
        val triggerSource: String,
        val attemptedAtMs: Long,
        val outcome: String,
        val existingState: String?,
        val retryCount: Int,
    )

    private val _lastClaim = MutableStateFlow<ClaimDiagnostic?>(null)
    val lastClaim: StateFlow<ClaimDiagnostic?> = _lastClaim.asStateFlow()

    /**
     * Attempts to claim [occurrenceKey] for [triggerSource]. Returns the
     * SAME [OccurrenceClaimOutcome] semantics [ProactiveOccurrenceReconciler]
     * defines — but this method, not the pure reconciler, is what actually
     * decides for real: the in-process [mutex] serializes same-process
     * races, and the atomic Room insert/update re-validates the condition
     * INSIDE the database write, so a race lost between reading and writing
     * (a genuinely concurrent second process) is caught honestly instead of
     * assumed away.
     */
    suspend fun claim(
        occurrenceKey: String,
        kind: String,
        logicalDate: LocalDate,
        triggerSource: String,
        now: Long = System.currentTimeMillis(),
    ): OccurrenceClaimOutcome = mutex.withLock {
        val existing = dao.find(occurrenceKey)
        val existingState = existing?.state?.toStateOrNull()
        val decision = ProactiveOccurrenceReconciler.decide(existingState, existing?.claimedAtMs, now, STALE_AFTER_MS)

        val result = when (decision) {
            is OccurrenceClaimOutcome.Claimed -> {
                val rowId = dao.tryInsertClaim(
                    ProactiveOccurrenceEntity(
                        occurrenceKey = occurrenceKey, kind = kind, logicalDate = logicalDate.toString(),
                        state = ProactiveOccurrenceState.CLAIMED.name, triggerSource = triggerSource, claimedAtMs = now,
                    ),
                )
                if (rowId == -1L) reReadAsAlreadyOwned(occurrenceKey) else decision
            }
            is OccurrenceClaimOutcome.TakeoverAllowed -> {
                val affected = dao.tryTakeover(
                    key = occurrenceKey, staleCutoffMs = now - STALE_AFTER_MS,
                    newState = ProactiveOccurrenceState.CLAIMED.name, triggerSource = triggerSource, nowMs = now,
                )
                if (affected == 0) reReadAsAlreadyOwned(occurrenceKey) else decision
            }
            is OccurrenceClaimOutcome.AlreadyOwned -> decision
        }

        _lastClaim.value = ClaimDiagnostic(
            occurrenceKey = occurrenceKey, logicalDate = logicalDate.toString(), triggerSource = triggerSource,
            attemptedAtMs = now, outcome = result.diagnosticLabel(), existingState = existingState?.name,
            retryCount = existing?.retryCount ?: 0,
        )
        result
    }

    /** § MICRO-PATCH 14.2.2 §9 — a bounded, read-only snapshot for diagnostics; never the briefing content. */
    data class OccurrenceSnapshot(val state: ProactiveOccurrenceState?, val owningTriggerSource: String?)

    /**
     * § MICRO-PATCH 14.2.2 — a READ-ONLY current-state check, never a claim
     * attempt: no mutex, no insert/takeover, no [ClaimDiagnostic] update.
     * For paths that must VERIFY (not compete for) today's occurrence
     * before acting — e.g. the post-delivery silent content refresh, which
     * must never compose/post anything unless the real delivery genuinely
     * already happened (§ [com.simone.jarvismobile.core.proactive.MorningRefreshGate]) —
     * and for device diagnostic receipts that want to show which trigger
     * source actually owns an occurrence a later trigger was denied.
     */
    suspend fun peek(key: String): OccurrenceSnapshot? = runCatching {
        dao.find(key)?.let { OccurrenceSnapshot(it.state.toStateOrNull(), it.triggerSource) }
    }.getOrNull()

    private suspend fun reReadAsAlreadyOwned(key: String): OccurrenceClaimOutcome {
        val fresh = dao.find(key)
        return OccurrenceClaimOutcome.AlreadyOwned(fresh?.state?.toStateOrNull() ?: ProactiveOccurrenceState.CLAIMED)
    }

    suspend fun markGenerated(key: String) = runCatching {
        dao.markGenerated(key, ProactiveOccurrenceState.GENERATED.name, System.currentTimeMillis())
    }

    suspend fun markDeliveryAttempt(key: String) = runCatching {
        dao.markDeliveryAttempt(key, ProactiveOccurrenceState.DELIVERY_PENDING.name, System.currentTimeMillis())
    }

    suspend fun markDelivered(key: String) = runCatching {
        dao.markDelivered(key, ProactiveOccurrenceState.DELIVERED.name, System.currentTimeMillis())
    }

    /** Releases a claim that turned out not to result in a delivery this run (e.g. the governor picked a different candidate, or quiet hours/budget skipped it) — never leaves a claimed-but-abandoned occurrence permanently blocking a later legitimate trigger. */
    suspend fun markFailedRetryable(key: String, reason: String?) = runCatching {
        dao.markFailed(key, ProactiveOccurrenceState.FAILED_RETRYABLE.name, reason?.take(MAX_REASON_CHARS))
    }

    suspend fun pruneOld(retentionDays: Long = RETENTION_DAYS, now: Long = System.currentTimeMillis()) = runCatching {
        dao.deleteOlderThan(now - retentionDays * DAY_MS)
    }

    private fun String.toStateOrNull(): ProactiveOccurrenceState? = runCatching { ProactiveOccurrenceState.valueOf(this) }.getOrNull()

    private fun OccurrenceClaimOutcome.diagnosticLabel(): String = when (this) {
        is OccurrenceClaimOutcome.Claimed -> "claimed"
        is OccurrenceClaimOutcome.TakeoverAllowed -> "takeover"
        is OccurrenceClaimOutcome.AlreadyOwned -> "suppressed_duplicate:${state.name}"
    }

    private companion object {
        /** Long enough that legitimate generation+delivery (network weather/health refresh included) never gets falsely reclaimed by a merely-slow concurrent trigger; short enough that a genuinely crashed process is recoverable the same morning (§M). */
        const val STALE_AFTER_MS = 10 * 60 * 1000L
        const val RETENTION_DAYS = 7L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MAX_REASON_CHARS = 120
    }
}
