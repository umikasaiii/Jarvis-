package com.simone.jarvismobile.responsibility

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1A. Plain in-memory implementation
 * of [ResponsibilityJournalDao] — same JVM-testable-fake pattern already
 * established by `com.simone.jarvismobile.proactive.FakeTriggerEvidenceDao`.
 */
class FakeResponsibilityJournalDao : ResponsibilityJournalDao {
    private val rows = mutableListOf<ResponsibilityJournalRowEntity>()
    private var nextId = 1L

    override suspend fun insert(entity: ResponsibilityJournalRowEntity): Long {
        val row = entity.copy(id = nextId++)
        rows.add(row)
        return row.id
    }

    override suspend fun recentForKey(key: String, limit: Int): List<ResponsibilityJournalRowEntity> =
        rows.filter { it.key == key }.sortedByDescending { it.id }.take(limit)

    override suspend fun pruneKey(key: String, keep: Int) {
        val keepIds = rows.filter { it.key == key }.sortedByDescending { it.id }.take(keep).map { it.id }.toSet()
        rows.removeAll { it.key == key && it.id !in keepIds }
    }

    override suspend fun deleteOlderThan(cutoffMs: Long): Int {
        val toRemove = rows.filter { it.atMs < cutoffMs }
        rows.removeAll(toRemove)
        return toRemove.size
    }

    fun all(): List<ResponsibilityJournalRowEntity> = rows.toList()
}
