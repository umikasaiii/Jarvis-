package com.simone.jarvismobile.proactive

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.1. Plain in-memory
 * implementation of [ProactiveOccurrenceDao] — Room `@Dao` interfaces are
 * plain Kotlin interfaces, so this lets [ProactiveOccurrenceStore] be tested
 * on the JVM with no Robolectric, mirroring
 * `com.simone.jarvismobile.automation.rule.FakeAutomationExecutionDao`'s
 * established pattern. Re-implements [ProactiveOccurrenceDao.tryTakeover]'s
 * conditional-UPDATE semantics by hand (single-threaded, so no atomicity
 * concerns here — the real Room/SQLite atomicity is what the production
 * query relies on, not this fake).
 */
class FakeProactiveOccurrenceDao : ProactiveOccurrenceDao {
    private val rows = mutableMapOf<String, ProactiveOccurrenceEntity>()

    override suspend fun find(key: String): ProactiveOccurrenceEntity? = rows[key]

    override suspend fun tryInsertClaim(entity: ProactiveOccurrenceEntity): Long {
        if (rows.containsKey(entity.occurrenceKey)) return -1L
        rows[entity.occurrenceKey] = entity
        return 1L
    }

    override suspend fun tryTakeover(
        key: String,
        staleCutoffMs: Long,
        newState: String,
        triggerSource: String,
        nowMs: Long,
    ): Int {
        val existing = rows[key] ?: return 0
        val eligible = existing.state in UNCONDITIONAL_TAKEOVER_STATES ||
            (existing.state in STALE_TAKEOVER_ELIGIBLE_STATES && existing.claimedAtMs <= staleCutoffMs)
        if (!eligible) return 0
        rows[key] = existing.copy(
            state = newState,
            triggerSource = triggerSource,
            claimedAtMs = nowMs,
            retryCount = existing.retryCount + 1,
            generatedAtMs = null,
            deliveryAttemptAtMs = null,
            deliveredAtMs = null,
            terminalReason = null,
        )
        return 1
    }

    override suspend fun markGenerated(key: String, state: String, atMs: Long, expectedClaimedAtMs: Long): Int {
        val existing = rows[key] ?: return 0
        if (existing.claimedAtMs != expectedClaimedAtMs) return 0
        rows[key] = existing.copy(state = state, generatedAtMs = atMs)
        return 1
    }

    override suspend fun markDeliveryAttempt(key: String, state: String, atMs: Long, expectedClaimedAtMs: Long): Int {
        val existing = rows[key] ?: return 0
        if (existing.claimedAtMs != expectedClaimedAtMs) return 0
        rows[key] = existing.copy(state = state, deliveryAttemptAtMs = atMs)
        return 1
    }

    override suspend fun markDelivered(key: String, state: String, atMs: Long, expectedClaimedAtMs: Long): Int {
        val existing = rows[key] ?: return 0
        if (existing.claimedAtMs != expectedClaimedAtMs) return 0
        rows[key] = existing.copy(state = state, deliveredAtMs = atMs)
        return 1
    }

    override suspend fun markFailed(key: String, state: String, reason: String?, expectedClaimedAtMs: Long): Int {
        val existing = rows[key] ?: return 0
        if (existing.claimedAtMs != expectedClaimedAtMs) return 0
        rows[key] = existing.copy(state = state, terminalReason = reason)
        return 1
    }

    override suspend fun deleteOlderThan(cutoffMs: Long): Int {
        val toRemove = rows.filterValues { it.claimedAtMs < cutoffMs }.keys.toList()
        toRemove.forEach { rows.remove(it) }
        return toRemove.size
    }

    /** Test-only seam — pretends a row already exists, as if another process had written it. */
    fun seed(entity: ProactiveOccurrenceEntity) {
        rows[entity.occurrenceKey] = entity
    }

    fun rowOrNull(key: String): ProactiveOccurrenceEntity? = rows[key]

    private companion object {
        /** § WORK PACKAGE A (P0-6) — mirrors the real Room query: `DELIVERY_PENDING` is NOT staleness-eligible, only `CLAIMED`/`GENERATED`. */
        val STALE_TAKEOVER_ELIGIBLE_STATES = setOf("CLAIMED", "GENERATED")

        /** § WORK PACKAGE A — proven no-effect states, always immediately retryable regardless of age. */
        val UNCONDITIONAL_TAKEOVER_STATES = setOf("FAILED_RETRYABLE", "BLOCKED_PERMISSION")
    }
}
