package com.simone.jarvismobile.responsibility

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1A. Plain in-memory implementation
 * of [ResponsibilityDao] — Room `@Dao` interfaces are plain Kotlin
 * interfaces, so this lets [ResponsibilityStore] be tested on the JVM with
 * no Robolectric, mirroring
 * `com.simone.jarvismobile.proactive.FakeProactiveOccurrenceDao`'s
 * established pattern. Re-implements [ResponsibilityDao.transitionIfRevisionMatches]'s
 * conditional-UPDATE semantics by hand (single-threaded, so no atomicity
 * concerns here — the real Room/SQLite atomicity is what the production
 * query relies on, not this fake).
 */
class FakeResponsibilityDao : ResponsibilityDao {
    private val rows = mutableMapOf<String, ResponsibilityEntity>()

    override suspend fun find(key: String): ResponsibilityEntity? = rows[key]

    override suspend fun tryInsert(entity: ResponsibilityEntity): Long {
        if (rows.containsKey(entity.logicalKey)) return -1L
        rows[entity.logicalKey] = entity
        return 1L
    }

    override suspend fun transitionIfRevisionMatches(
        key: String,
        expectedRevision: Long,
        newRevision: Long,
        lifecycleState: String,
        updatedAtMs: Long,
        lastDecision: String?,
        lastVerificationOutcome: String?,
        recheckAtMs: Long?,
        terminalReason: String?,
    ): Int {
        val existing = rows[key] ?: return 0
        if (existing.revision != expectedRevision) return 0
        rows[key] = existing.copy(
            lifecycleState = lifecycleState,
            revision = newRevision,
            updatedAtMs = updatedAtMs,
            lastDecision = lastDecision,
            lastVerificationOutcome = lastVerificationOutcome,
            recheckAtMs = recheckAtMs,
            terminalReason = terminalReason,
        )
        return 1
    }

    override suspend fun pruneTerminalOlderThan(cutoffMs: Long): Int {
        val toRemove = rows.filterValues { it.lifecycleState in TERMINAL_STATES && it.updatedAtMs < cutoffMs }.keys.toList()
        toRemove.forEach { rows.remove(it) }
        return toRemove.size
    }

    /** Test-only seam — pretends a row already exists/was overwritten, as if another process had written it. */
    fun seed(entity: ResponsibilityEntity) {
        rows[entity.logicalKey] = entity
    }

    fun rowOrNull(key: String): ResponsibilityEntity? = rows[key]

    private companion object {
        val TERMINAL_STATES = setOf("COMPLETED", "FAILED", "EXPIRED")
    }
}

/**
 * § PA-1A. A decorator that simulates a concurrent writer racing between
 * [ResponsibilityStore]'s read ([find]) and its later CAS write — deterministic
 * without real threads: the first [find] call after [raceOnNextFind] is set
 * mutates the underlying row's revision, so the revision the store captured
 * is already stale by the time it tries to commit.
 */
class RacingResponsibilityDao(private val delegate: FakeResponsibilityDao) : ResponsibilityDao by delegate {
    var raceOnNextFind: Boolean = false

    override suspend fun find(key: String): ResponsibilityEntity? {
        val result = delegate.find(key)
        if (raceOnNextFind && result != null) {
            raceOnNextFind = false
            delegate.seed(result.copy(revision = result.revision + 1))
        }
        return result
    }
}
