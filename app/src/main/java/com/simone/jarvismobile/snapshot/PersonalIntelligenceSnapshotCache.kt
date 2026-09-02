package com.simone.jarvismobile.snapshot

import com.simone.jarvismobile.core.snapshot.PersonalIntelligenceSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short TTL cache in front of [PersonalIntelligenceSnapshotBuilder] (§
 * richiesta esplicita §19: "evitare di ricostruire inutilmente tutto lo
 * snapshot ad ogni messaggio... NON introdurre polling continuo"). A single
 * in-flight build is shared by concurrent callers via [mutex] rather than
 * racing multiple rebuilds.
 *
 * **Onestà**: invalidation here is TTL-only, not fully event-driven across
 * every source (§ TODO nel report finale) — [invalidate] is exposed for a
 * future caller that knows a source changed significantly, but nothing
 * calls it automatically yet. A short TTL ([CACHE_TTL_MS]) is deliberately
 * small enough that this is a minor staleness window, not stale data
 * masquerading as current.
 */
@Singleton
class PersonalIntelligenceSnapshotCache @Inject constructor(
    private val builder: PersonalIntelligenceSnapshotBuilder,
) {
    @Volatile private var cached: PersonalIntelligenceSnapshot? = null
    @Volatile private var cachedAtMs: Long = 0L
    private val mutex = Mutex()

    suspend fun get(forceRebuild: Boolean = false): PersonalIntelligenceSnapshot = mutex.withLock {
        val current = cached
        val age = System.currentTimeMillis() - cachedAtMs
        if (!forceRebuild && current != null && age in 0..CACHE_TTL_MS) return@withLock current
        val fresh = builder.build()
        cached = fresh
        cachedAtMs = System.currentTimeMillis()
        fresh
    }

    fun invalidate() {
        cached = null
    }

    private companion object {
        const val CACHE_TTL_MS = 20_000L
    }
}
