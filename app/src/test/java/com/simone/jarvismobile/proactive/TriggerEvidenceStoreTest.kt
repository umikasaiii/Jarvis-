package com.simone.jarvismobile.proactive

import com.simone.jarvismobile.core.proactive.TriggerEvidencePolicy
import com.simone.jarvismobile.core.proactive.TriggerEvidenceSource
import com.simone.jarvismobile.core.proactive.TriggerEvidenceStage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.3 §4/§5/§13. JVM
 * tests for [TriggerEvidenceStore] against the plain in-memory
 * [FakeTriggerEvidenceDao] — no Robolectric needed, same pattern already
 * established for [ProactiveOccurrenceStoreTest].
 */
class TriggerEvidenceStoreTest {

    @Test
    fun `a recorded entry survives a fresh store instance on the same dao - process-restart safety`() = runTest {
        val dao = FakeTriggerEvidenceDao()
        TriggerEvidenceStore(dao).record(TriggerEvidenceSource.FIRST_UNLOCK, TriggerEvidenceStage.SERVICE_ON_CREATE)

        // A brand-new store (as a process restart would produce) still sees the row.
        val restarted = TriggerEvidenceStore(dao)
        val recent = restarted.recent(TriggerEvidenceSource.FIRST_UNLOCK)
        assertEquals(1, recent.size)
        assertEquals(TriggerEvidenceStage.SERVICE_ON_CREATE, recent.first().stage)
    }

    @Test
    fun `recent(source) never returns entries from a different source`() = runTest {
        val dao = FakeTriggerEvidenceDao()
        val store = TriggerEvidenceStore(dao)
        store.record(TriggerEvidenceSource.NEXT_ALARM, TriggerEvidenceStage.NEXT_ALARM_SCHEDULED)
        store.record(TriggerEvidenceSource.CONFIGURED_TIME, TriggerEvidenceStage.CONFIGURED_TIME_SCHEDULED)

        val nextAlarm = store.recent(TriggerEvidenceSource.NEXT_ALARM)
        assertEquals(1, nextAlarm.size)
        assertEquals(TriggerEvidenceSource.NEXT_ALARM, nextAlarm.first().source)
    }

    @Test
    fun `pruning keeps only the newest MAX_ENTRIES_PER_SOURCE rows for that source`() = runTest {
        val dao = FakeTriggerEvidenceDao()
        val store = TriggerEvidenceStore(dao)
        repeat(TriggerEvidencePolicy.MAX_ENTRIES_PER_SOURCE + 15) { i ->
            store.record(TriggerEvidenceSource.FIRST_UNLOCK, TriggerEvidenceStage.USER_PRESENT_OBSERVED, detail = "i=$i")
        }
        assertEquals(TriggerEvidencePolicy.MAX_ENTRIES_PER_SOURCE, dao.all().count { it.source == "FIRST_UNLOCK" })
        // The newest entry survives, not an arbitrary one.
        val newest = store.recent(TriggerEvidenceSource.FIRST_UNLOCK, limit = 1).first()
        assertEquals("i=${TriggerEvidencePolicy.MAX_ENTRIES_PER_SOURCE + 14}", newest.detail)
    }

    @Test
    fun `pruning one source never touches another source's rows`() = runTest {
        val dao = FakeTriggerEvidenceDao()
        val store = TriggerEvidenceStore(dao)
        repeat(TriggerEvidencePolicy.MAX_ENTRIES_PER_SOURCE + 5) {
            store.record(TriggerEvidenceSource.FIRST_UNLOCK, TriggerEvidenceStage.USER_PRESENT_OBSERVED)
        }
        store.record(TriggerEvidenceSource.NEXT_ALARM, TriggerEvidenceStage.NEXT_ALARM_SCHEDULED)
        assertEquals(1, dao.all().count { it.source == "NEXT_ALARM" })
    }

    @Test
    fun `detail is sanitized before persistence - never a raw unbounded string`() = runTest {
        val dao = FakeTriggerEvidenceDao()
        val store = TriggerEvidenceStore(dao)
        val long = "x".repeat(500)
        store.record(TriggerEvidenceSource.NEXT_ALARM, TriggerEvidenceStage.NEXT_ALARM_SCHEDULED, detail = long)
        val stored = dao.all().single().detail!!
        assertEquals(TriggerEvidencePolicy.MAX_DETAIL_CHARS, stored.length)
    }

    @Test
    fun `a corrupted row (unknown enum name) is skipped, never crashes the read`() = runTest {
        val dao = FakeTriggerEvidenceDao()
        dao.insert(TriggerEvidenceRowEntity(eventAtMs = 1L, processSessionId = "abc", source = "NOT_A_REAL_SOURCE", stage = "SERVICE_ON_CREATE", detail = null))
        val store = TriggerEvidenceStore(dao)
        assertTrue(store.recentAll().isEmpty())
    }

    @Test
    fun `pruneOld removes entries past the retention window regardless of source`() = runTest {
        val dao = FakeTriggerEvidenceDao()
        val store = TriggerEvidenceStore(dao)
        val now = 10_000_000L
        dao.insert(TriggerEvidenceRowEntity(eventAtMs = 0L, processSessionId = "abc", source = "FIRST_UNLOCK", stage = "SERVICE_ON_CREATE", detail = null))
        store.record(TriggerEvidenceSource.FIRST_UNLOCK, TriggerEvidenceStage.USER_PRESENT_OBSERVED, now = now)
        store.pruneOld(retentionDays = 1, now = now)
        val remaining = dao.all()
        assertEquals(1, remaining.size)
        assertEquals(now, remaining.first().eventAtMs)
    }

    @Test
    fun `recording never throws even if the dao itself fails`() = runTest {
        val failingDao = object : TriggerEvidenceDao {
            override suspend fun insert(entity: TriggerEvidenceRowEntity): Long = throw IllegalStateException("boom")
            override suspend fun recentBySource(source: String, limit: Int): List<TriggerEvidenceRowEntity> = throw IllegalStateException("boom")
            override suspend fun recentAll(limit: Int): List<TriggerEvidenceRowEntity> = throw IllegalStateException("boom")
            override suspend fun pruneSource(source: String, keep: Int) = throw IllegalStateException("boom")
            override suspend fun deleteOlderThan(cutoffMs: Long): Int = throw IllegalStateException("boom")
        }
        val store = TriggerEvidenceStore(failingDao)
        // Must not throw — a diagnostic write failing must never look like the trigger itself failing.
        store.record(TriggerEvidenceSource.FIRST_UNLOCK, TriggerEvidenceStage.SERVICE_ON_CREATE)
        assertEquals(emptyList<Any>(), store.recentAll())
    }

    @Test
    fun `each store instance keeps its own processSessionId, distinguishing receipts across a restart`() = runTest {
        val dao = FakeTriggerEvidenceDao()
        val first = TriggerEvidenceStore(dao)
        first.record(TriggerEvidenceSource.FIRST_UNLOCK, TriggerEvidenceStage.SERVICE_ON_CREATE)
        val second = TriggerEvidenceStore(dao)
        second.record(TriggerEvidenceSource.FIRST_UNLOCK, TriggerEvidenceStage.SERVICE_ON_CREATE)

        val recorded = second.recent(TriggerEvidenceSource.FIRST_UNLOCK, limit = 10)
        assertEquals(2, recorded.size)
        assertTrue(recorded.map { it.processSessionId }.toSet().size == 2)
    }
}
