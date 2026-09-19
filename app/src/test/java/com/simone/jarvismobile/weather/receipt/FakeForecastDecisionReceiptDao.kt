package com.simone.jarvismobile.weather.receipt

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D.1 §8 (W10). Plain in-memory implementation of
 * [ForecastDecisionReceiptDao] — Room `@Dao` interfaces are plain Kotlin
 * interfaces, so this lets [ForecastDecisionReceiptRepository] be exercised
 * on the JVM with no Robolectric, mirroring the same established pattern
 * already used by `FakeProactiveOccurrenceDao`/`FakeAutomationExecutionDao`
 * elsewhere in this project. `rowSeq` autoGenerate is reproduced by hand
 * (single-threaded, so no atomicity concerns here — the real Room/SQLite
 * `INTEGER PRIMARY KEY AUTOINCREMENT`-equivalent guarantee is what the
 * production schema relies on, not this fake) as a strictly-increasing
 * counter, proving §23's "append-only, never a mutable rewrite" sequence
 * property is a real behavior of the repository, not just a doc comment.
 */
class FakeForecastDecisionReceiptDao : ForecastDecisionReceiptDao {
    private val rows = mutableListOf<ForecastDecisionReceiptEntity>()
    private var nextRowSeq = 1L

    override suspend fun insert(entity: ForecastDecisionReceiptEntity): Long {
        val assigned = entity.copy(rowSeq = nextRowSeq++)
        rows += assigned
        return assigned.rowSeq
    }

    override suspend fun find(rowSeq: Long): ForecastDecisionReceiptEntity? =
        rows.firstOrNull { it.rowSeq == rowSeq }

    override suspend fun findByReceiptId(receiptId: String): ForecastDecisionReceiptEntity? =
        rows.firstOrNull { it.receiptId == receiptId }

    override suspend fun updateOutcomeEvents(rowSeq: Long, outcomeEventsJson: String): Int {
        val idx = rows.indexOfFirst { it.rowSeq == rowSeq }
        if (idx < 0) return 0
        rows[idx] = rows[idx].copy(outcomeEventsJson = outcomeEventsJson)
        return 1
    }

    override suspend fun recent(limit: Int): List<ForecastDecisionReceiptEntity> =
        rows.sortedByDescending { it.rowSeq }.take(limit)

    override suspend fun count(): Int = rows.size

    override suspend fun deleteOlderThan(cutoffMs: Long): Int {
        val before = rows.size
        rows.removeAll { it.evaluatedAtUtcMs < cutoffMs }
        return before - rows.size
    }

    override suspend fun deleteOldestExcess(excess: Int): Int {
        if (excess <= 0) return 0
        val toRemove = rows.sortedBy { it.rowSeq }.take(excess).map { it.rowSeq }.toSet()
        val before = rows.size
        rows.removeAll { it.rowSeq in toRemove }
        return before - rows.size
    }

    /** Test-only seam — every row currently held, in insertion (rowSeq) order. */
    fun allRowsInSequence(): List<ForecastDecisionReceiptEntity> = rows.sortedBy { it.rowSeq }
}
