package com.simone.jarvismobile.responsibility

import com.simone.jarvismobile.core.responsibility.ResponsibilityDecision
import com.simone.jarvismobile.core.responsibility.ResponsibilityJournalEntry
import com.simone.jarvismobile.core.responsibility.ResponsibilityJournalPolicy
import com.simone.jarvismobile.core.responsibility.ResponsibilityKey
import com.simone.jarvismobile.core.responsibility.ResponsibilityLifecycle
import com.simone.jarvismobile.core.responsibility.ResponsibilityLifecycleState
import com.simone.jarvismobile.core.responsibility.ResponsibilityRecord
import com.simone.jarvismobile.core.responsibility.ResponsibilityTransitionRejection
import com.simone.jarvismobile.core.responsibility.ResponsibilityTransitionResult
import com.simone.jarvismobile.core.responsibility.ResponsibilityType
import com.simone.jarvismobile.core.responsibility.ResponsibilityVerificationOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1A. The ONE app-side owner of
 * durable Responsibility records and their journal — mirrors
 * [com.simone.jarvismobile.proactive.ProactiveOccurrenceStore]'s two-layer
 * defense: an in-process [Mutex] (the fast path for two callers racing
 * inside the SAME process) backed by a Room row whose `@PrimaryKey`
 * (creation) and conditional `UPDATE ... WHERE revision = :expected`
 * (every later transition) give a genuine cross-process atomic guarantee.
 *
 * PA-1A ships this store as generic, reusable infrastructure only — nothing
 * in this pass creates a real [ResponsibilityKey], decides a
 * [ResponsibilityDecision], or calls [transition] from a live Morning
 * Briefing/automation/tool-execution path. That wiring (observation
 * reducer, `ResponsibilityDecisionPolicy`/`MorningAssistancePolicy`, the
 * MORNING_ASSISTANCE vertical slice) is PA-2 — see §65.1's own sequencing
 * and ADR-014's "IMPLEMENTATION NOT STARTED" marker.
 */
@Singleton
class ResponsibilityStore @Inject constructor(
    private val dao: ResponsibilityDao,
    private val journalDao: ResponsibilityJournalDao,
) {
    private val mutex = Mutex()

    /** The result of attempting to create a new logical Responsibility. */
    sealed interface CreateOutcome {
        data class Created(val record: ResponsibilityRecord) : CreateOutcome
        data class AlreadyExists(val record: ResponsibilityRecord) : CreateOutcome
    }

    /**
     * Creates a new Responsibility at [initialState] if (and only if) no row
     * for [key] already exists. Two concurrent callers racing on the same
     * [key] always converge on exactly one [CreateOutcome.Created] and every
     * other caller sees [CreateOutcome.AlreadyExists] with the real winner's
     * state — never two independently-initialized rows.
     */
    suspend fun createIfAbsent(
        key: ResponsibilityKey,
        initialState: ResponsibilityLifecycleState = ResponsibilityLifecycleState.WAITING,
        priority: Int = 0,
        deadlineAtMs: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): CreateOutcome = mutex.withLock {
        val keyString = key.toString()
        dao.find(keyString)?.toRecordOrNull()?.let { return@withLock CreateOutcome.AlreadyExists(it) }

        val entity = ResponsibilityEntity(
            logicalKey = keyString,
            type = key.type.name,
            lifecycleState = initialState.name,
            revision = INITIAL_REVISION,
            createdAtMs = now,
            updatedAtMs = now,
            priority = priority,
            deadlineAtMs = deadlineAtMs,
        )
        val record = ResponsibilityRecord(
            key = key,
            lifecycleState = initialState,
            revision = INITIAL_REVISION,
            createdAtMs = now,
            updatedAtMs = now,
            priority = priority,
            deadlineAtMs = deadlineAtMs,
        )
        val rowId = dao.tryInsert(entity)
        if (rowId == -1L) {
            // Lost a race to a concurrent creator between the read above and
            // this insert — report the real winner's state honestly instead
            // of assuming our own shape ever landed.
            val fresh = dao.find(keyString)?.toRecordOrNull() ?: record
            return@withLock CreateOutcome.AlreadyExists(fresh)
        }

        appendJournal(
            keyString = keyString, type = key.type, fromState = null, toState = initialState,
            decision = null, verificationOutcome = null, reasonCode = "created", now = now,
        )
        CreateOutcome.Created(record)
    }

    /** A read-only lookup — never a claim, never a mutation. `null` if no such Responsibility exists (or a corrupt row failed to parse — never thrown). */
    suspend fun get(key: ResponsibilityKey): ResponsibilityRecord? = runCatching {
        dao.find(key.toString())?.toRecordOrNull()
    }.getOrNull()

    /** The result of a [transition] attempt. */
    sealed interface TransitionOutcome {
        data class Applied(val record: ResponsibilityRecord) : TransitionOutcome
        data class Rejected(val reason: ResponsibilityTransitionRejection) : TransitionOutcome

        /** No row exists for the given key — [createIfAbsent] must run first. */
        data object NotFound : TransitionOutcome

        /**
         * The caller's view of the record was superseded by a concurrent
         * transition (the CAS `UPDATE` matched zero rows) — exactly like
         * [com.simone.jarvismobile.proactive.ProactiveOccurrenceStore]'s
         * `mark*` methods returning `false`, callers MUST treat this as "I
         * am no longer working from the current revision", never retry
         * blindly from stale data.
         */
        data object StaleRevision : TransitionOutcome
    }

    /**
     * Attempts to move the Responsibility at [key] to [to]. The pure
     * [ResponsibilityLifecycle.transition] decides legality first (so an
     * illegal edge is rejected before ever touching the database); only a
     * legal transition is then committed via
     * [ResponsibilityDao.transitionIfRevisionMatches]'s CAS fencing — a
     * concurrent transition that already moved the row surfaces honestly as
     * [TransitionOutcome.StaleRevision], never a silent overwrite.
     */
    suspend fun transition(
        key: ResponsibilityKey,
        to: ResponsibilityLifecycleState,
        decision: ResponsibilityDecision? = null,
        verificationOutcome: ResponsibilityVerificationOutcome? = null,
        recheckAtMs: Long? = null,
        terminalReason: String? = null,
        reasonCode: String? = null,
        now: Long = System.currentTimeMillis(),
    ): TransitionOutcome = mutex.withLock {
        val keyString = key.toString()
        val existing = dao.find(keyString)?.toRecordOrNull() ?: return@withLock TransitionOutcome.NotFound

        val pure = ResponsibilityLifecycle.transition(
            record = existing, to = to, now = now, revisionAfter = existing.revision + 1,
            decision = decision, verificationOutcome = verificationOutcome, terminalReason = terminalReason,
        )
        when (pure) {
            is ResponsibilityTransitionResult.Rejected -> TransitionOutcome.Rejected(pure.reason)
            is ResponsibilityTransitionResult.Applied -> {
                val resolvedRecheckAtMs = recheckAtMs ?: pure.record.recheckAtMs
                val affected = dao.transitionIfRevisionMatches(
                    key = keyString,
                    expectedRevision = existing.revision,
                    newRevision = pure.record.revision,
                    lifecycleState = pure.record.lifecycleState.name,
                    updatedAtMs = pure.record.updatedAtMs,
                    lastDecision = pure.record.lastDecision?.name,
                    lastVerificationOutcome = pure.record.lastVerificationOutcome?.name,
                    recheckAtMs = resolvedRecheckAtMs,
                    terminalReason = pure.record.terminalReason,
                )
                if (affected == 0) {
                    TransitionOutcome.StaleRevision
                } else {
                    appendJournal(
                        keyString = keyString, type = key.type, fromState = existing.lifecycleState,
                        toState = pure.record.lifecycleState, decision = decision,
                        verificationOutcome = verificationOutcome, reasonCode = reasonCode, now = now,
                    )
                    TransitionOutcome.Applied(pure.record.copy(recheckAtMs = resolvedRecheckAtMs))
                }
            }
        }
    }

    /** The most recent bounded journal entries for [key], newest first. Never throws; an unparseable row is dropped rather than surfaced. */
    suspend fun journal(key: ResponsibilityKey, limit: Int = 20): List<ResponsibilityJournalEntry> = runCatching {
        journalDao.recentForKey(key.toString(), limit).mapNotNull { it.toEntryOrNull() }
    }.getOrDefault(emptyList())

    /**
     * Bounded retention: terminal Responsibility rows older than
     * [retentionDays] (never an in-flight one — see
     * [ResponsibilityDao.pruneTerminalOlderThan]'s doc comment) and journal
     * rows older than [retentionDays], independent of their per-key cap
     * already enforced on every [appendJournal] call.
     */
    suspend fun prune(retentionDays: Long = ResponsibilityJournalPolicy.DEFAULT_RETENTION_DAYS, now: Long = System.currentTimeMillis()) {
        runCatching {
            val cutoffMs = now - retentionDays * DAY_MS
            dao.pruneTerminalOlderThan(cutoffMs)
            journalDao.deleteOlderThan(cutoffMs)
        }
    }

    private suspend fun appendJournal(
        keyString: String,
        type: ResponsibilityType,
        fromState: ResponsibilityLifecycleState?,
        toState: ResponsibilityLifecycleState,
        decision: ResponsibilityDecision?,
        verificationOutcome: ResponsibilityVerificationOutcome?,
        reasonCode: String?,
        now: Long,
    ) {
        runCatching {
            journalDao.insert(
                ResponsibilityJournalRowEntity(
                    atMs = now,
                    key = keyString,
                    type = type.name,
                    fromState = fromState?.name,
                    toState = toState.name,
                    decision = decision?.name,
                    verificationOutcome = verificationOutcome?.name,
                    reasonCode = ResponsibilityJournalPolicy.sanitizeReasonCode(reasonCode),
                ),
            )
            journalDao.pruneKey(keyString, ResponsibilityJournalPolicy.MAX_ENTRIES_PER_KEY)
        }
    }

    private fun ResponsibilityEntity.toRecordOrNull(): ResponsibilityRecord? {
        val parsedKey = ResponsibilityKey.parse(logicalKey) ?: return null
        val parsedState = runCatching { ResponsibilityLifecycleState.valueOf(lifecycleState) }.getOrNull() ?: return null
        val parsedDecision = lastDecision?.let { raw -> runCatching { ResponsibilityDecision.valueOf(raw) }.getOrNull() }
        val parsedVerification = lastVerificationOutcome?.let { raw -> runCatching { ResponsibilityVerificationOutcome.valueOf(raw) }.getOrNull() }
        return runCatching {
            ResponsibilityRecord(
                key = parsedKey,
                lifecycleState = parsedState,
                revision = revision,
                createdAtMs = createdAtMs,
                updatedAtMs = updatedAtMs,
                priority = priority,
                deadlineAtMs = deadlineAtMs,
                recheckAtMs = recheckAtMs,
                lastDecision = parsedDecision,
                lastVerificationOutcome = parsedVerification,
                linkedActionOccurrenceKey = linkedActionOccurrenceKey,
                terminalReason = terminalReason,
            )
        }.getOrNull()
    }

    private fun ResponsibilityJournalRowEntity.toEntryOrNull(): ResponsibilityJournalEntry? {
        val parsedType = runCatching { ResponsibilityType.valueOf(type) }.getOrNull() ?: return null
        val parsedToState = runCatching { ResponsibilityLifecycleState.valueOf(toState) }.getOrNull() ?: return null
        val parsedFromState = fromState?.let { raw -> runCatching { ResponsibilityLifecycleState.valueOf(raw) }.getOrNull() }
        val parsedDecision = decision?.let { raw -> runCatching { ResponsibilityDecision.valueOf(raw) }.getOrNull() }
        val parsedVerification = verificationOutcome?.let { raw -> runCatching { ResponsibilityVerificationOutcome.valueOf(raw) }.getOrNull() }
        return ResponsibilityJournalEntry(
            atMs = atMs, key = key, type = parsedType, fromState = parsedFromState, toState = parsedToState,
            decision = parsedDecision, verificationOutcome = parsedVerification, reasonCode = reasonCode,
        )
    }

    private companion object {
        const val INITIAL_REVISION = 1L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
