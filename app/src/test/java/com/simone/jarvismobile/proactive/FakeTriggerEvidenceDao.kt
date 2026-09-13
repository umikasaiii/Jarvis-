package com.simone.jarvismobile.proactive

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.3 §4/§5. Plain
 * in-memory implementation of [TriggerEvidenceDao] — same JVM-testable-fake
 * pattern already established by [FakeProactiveOccurrenceDao].
 */
class FakeTriggerEvidenceDao : TriggerEvidenceDao {
    private val rows = mutableListOf<TriggerEvidenceRowEntity>()
    private var nextId = 1L

    override suspend fun insert(entity: TriggerEvidenceRowEntity): Long {
        val row = entity.copy(id = nextId++)
        rows.add(row)
        return row.id
    }

    override suspend fun recentBySource(source: String, limit: Int): List<TriggerEvidenceRowEntity> =
        rows.filter { it.source == source }.sortedByDescending { it.id }.take(limit)

    override suspend fun recentAll(limit: Int): List<TriggerEvidenceRowEntity> =
        rows.sortedByDescending { it.id }.take(limit)

    override suspend fun pruneSource(source: String, keep: Int) {
        val keepIds = rows.filter { it.source == source }.sortedByDescending { it.id }.take(keep).map { it.id }.toSet()
        rows.removeAll { it.source == source && it.id !in keepIds }
    }

    override suspend fun deleteOlderThan(cutoffMs: Long): Int {
        val toRemove = rows.filter { it.eventAtMs < cutoffMs }
        rows.removeAll(toRemove)
        return toRemove.size
    }

    fun all(): List<TriggerEvidenceRowEntity> = rows.toList()
}
