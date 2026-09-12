package com.simone.jarvismobile.proactive

import com.simone.jarvismobile.core.proactive.OccurrenceClaimOutcome
import com.simone.jarvismobile.core.proactive.ProactiveOccurrenceState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.1 §V. Store-level
 * integration tests for the durable morning-digest occurrence claim, using
 * [FakeProactiveOccurrenceDao] — plain JVM, no Robolectric, same pattern as
 * `DurableDedupTest.kt`.
 */
class ProactiveOccurrenceStoreTest {

    private val date = LocalDate.of(2026, 9, 9)
    private val key = "MORNING_DIGEST:$date"
    private val now = 2_000_000_000L

    @Test
    fun `a fresh claim on an empty table succeeds`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        assertEquals(OccurrenceClaimOutcome.Claimed, outcome)
        val row = dao.rowOrNull(key)
        assertNotNull(row)
        assertEquals(ProactiveOccurrenceState.CLAIMED.name, row!!.state)
        assertEquals("FIRST_UNLOCK", row.triggerSource)
    }

    @Test
    fun `a second claim after the first wins is a no-op loser - no side effect`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val first = store.claim(key, "MORNING_DIGEST", date, "NEXT_ALARM", now)
        val rowAfterFirst = dao.rowOrNull(key)!!.copy()

        val second = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now + 1_000)

        assertEquals(OccurrenceClaimOutcome.Claimed, first)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.CLAIMED), second)
        // the loser must perform NO side effect: the row is unchanged
        assertEquals(rowAfterFirst, dao.rowOrNull(key))
    }

    @Test
    fun `DELIVERED suppresses every later trigger regardless of source`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = date.toString(),
                state = ProactiveOccurrenceState.DELIVERED.name, triggerSource = "FIRST_UNLOCK",
                claimedAtMs = now - 999_999_999, deliveredAtMs = now - 999_999_000,
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), outcome)
    }

    @Test
    fun `FAILED_RETRYABLE allows an immediate real takeover`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = date.toString(),
                state = ProactiveOccurrenceState.FAILED_RETRYABLE.name, triggerSource = "FIRST_UNLOCK",
                claimedAtMs = now - 1_000, retryCount = 1, terminalReason = "skip:quiet_hours",
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        assertEquals(OccurrenceClaimOutcome.TakeoverAllowed, outcome)
        val row = dao.rowOrNull(key)!!
        assertEquals(ProactiveOccurrenceState.CLAIMED.name, row.state)
        assertEquals("CONFIGURED_TIME", row.triggerSource)
        assertEquals(2, row.retryCount)
        assertNull(row.terminalReason)
    }

    @Test
    fun `a fresh in-flight row - UNKNOWN outcome window - is never taken over`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = date.toString(),
                state = ProactiveOccurrenceState.DELIVERY_PENDING.name, triggerSource = "FIRST_UNLOCK",
                claimedAtMs = now - 1_000,
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERY_PENDING), outcome)
    }

    @Test
    fun `a stale in-flight row past the staleness threshold allows takeover - crash recovery`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val staleAfterMs = 10 * 60 * 1000L
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = date.toString(),
                state = ProactiveOccurrenceState.CLAIMED.name, triggerSource = "FIRST_UNLOCK",
                claimedAtMs = now - staleAfterMs - 60_000,
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "PERIODIC_FALLBACK", now)
        assertEquals(OccurrenceClaimOutcome.TakeoverAllowed, outcome)
        assertEquals(ProactiveOccurrenceState.CLAIMED.name, dao.rowOrNull(key)!!.state)
    }

    @Test
    fun `different logical dates never collide`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val today = store.claim("MORNING_DIGEST:2026-09-09", "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        val tomorrow = store.claim("MORNING_DIGEST:2026-09-10", "MORNING_DIGEST", date.plusDays(1), "FIRST_UNLOCK", now)
        assertEquals(OccurrenceClaimOutcome.Claimed, today)
        assertEquals(OccurrenceClaimOutcome.Claimed, tomorrow)
    }

    @Test
    fun `markGenerated then markDeliveryAttempt then markDelivered progress the state machine`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)

        store.markGenerated(key)
        assertEquals(ProactiveOccurrenceState.GENERATED.name, dao.rowOrNull(key)!!.state)
        assertNotNull(dao.rowOrNull(key)!!.generatedAtMs)

        store.markDeliveryAttempt(key)
        assertEquals(ProactiveOccurrenceState.DELIVERY_PENDING.name, dao.rowOrNull(key)!!.state)
        assertNotNull(dao.rowOrNull(key)!!.deliveryAttemptAtMs)

        store.markDelivered(key)
        assertEquals(ProactiveOccurrenceState.DELIVERED.name, dao.rowOrNull(key)!!.state)
        assertNotNull(dao.rowOrNull(key)!!.deliveredAtMs)
    }

    @Test
    fun `markFailedRetryable releases the claim so a later trigger can retry`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        store.markFailedRetryable(key, "skip:budget_exhausted")
        assertEquals(ProactiveOccurrenceState.FAILED_RETRYABLE.name, dao.rowOrNull(key)!!.state)

        val retry = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now + 60_000)
        assertEquals(OccurrenceClaimOutcome.TakeoverAllowed, retry)
    }

    @Test
    fun `pruneOld deletes rows older than the retention window`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        dao.seed(
            ProactiveOccurrenceEntity(
                occurrenceKey = "MORNING_DIGEST:2020-01-01", kind = "MORNING_DIGEST", logicalDate = "2020-01-01",
                state = ProactiveOccurrenceState.DELIVERED.name, triggerSource = "FIRST_UNLOCK", claimedAtMs = 0L,
            ),
        )
        val store = ProactiveOccurrenceStore(dao)
        store.pruneOld(retentionDays = 7, now = now)
        assertNull(dao.rowOrNull("MORNING_DIGEST:2020-01-01"))
    }

    @Test
    fun `a lost race between read and atomic insert is honestly re-reported as AlreadyOwned`() = runTest {
        // Simulates a genuinely concurrent cross-process claim: the store's own
        // read sees no row (existingState == null -> Claimed decision), but the
        // atomic DB insert loses the real race and returns -1L — the store must
        // re-read and report the truth, never silently proceed as if it owned it.
        val dao = object : ProactiveOccurrenceDao {
            private val delegate = FakeProactiveOccurrenceDao()
            private var findCalls = 0

            override suspend fun find(key: String): ProactiveOccurrenceEntity? {
                findCalls++
                return if (findCalls == 1) {
                    null
                } else {
                    delegate.seed(
                        ProactiveOccurrenceEntity(
                            occurrenceKey = key, kind = "MORNING_DIGEST", logicalDate = "2026-09-09",
                            state = ProactiveOccurrenceState.CLAIMED.name, triggerSource = "OTHER_PROCESS",
                            claimedAtMs = now,
                        ),
                    )
                    delegate.find(key)
                }
            }

            override suspend fun tryInsertClaim(entity: ProactiveOccurrenceEntity): Long = -1L

            override suspend fun tryTakeover(key: String, staleCutoffMs: Long, newState: String, triggerSource: String, nowMs: Long): Int = 0

            override suspend fun markGenerated(key: String, state: String, atMs: Long) {}
            override suspend fun markDeliveryAttempt(key: String, state: String, atMs: Long) {}
            override suspend fun markDelivered(key: String, state: String, atMs: Long) {}
            override suspend fun markFailed(key: String, state: String, reason: String?) {}
            override suspend fun deleteOlderThan(cutoffMs: Long): Int = 0
        }
        val store = ProactiveOccurrenceStore(dao)
        val outcome = store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        assertTrue(outcome is OccurrenceClaimOutcome.AlreadyOwned)
        assertEquals(ProactiveOccurrenceState.CLAIMED, (outcome as OccurrenceClaimOutcome.AlreadyOwned).state)
    }

    @Test
    fun `lastClaim diagnostic reflects the most recent claim decision without ever carrying briefing text`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        val diag = store.lastClaim.value
        assertNotNull(diag)
        assertEquals(key, diag!!.occurrenceKey)
        assertEquals("FIRST_UNLOCK", diag.triggerSource)
        assertEquals("claimed", diag.outcome)
    }

    // --- § PASSAGGIO 14.2 — the store is key-agnostic: the exact same
    // atomic claim/dedup/retry mechanism already proven above for the
    // morning-digest key works identically for a WEATHER_ALERT-shaped key,
    // with no changes to ProactiveOccurrenceStore/Dao needed. One targeted
    // test closes the loop instead of only asserting the key FORMAT (§
    // ProactiveOccurrenceKeyTest in :core) without proving real reuse. ---

    @Test
    fun `a weatherAlert-shaped key claims, delivers, and suppresses a second concurrent evaluation - real reuse, not just a matching format`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val weatherKey = "WEATHER_ALERT:2026-09-11"

        val first = store.claim(weatherKey, "WEATHER_ALERT", LocalDate.of(2026, 9, 11), "FIRST_UNLOCK", now)
        assertEquals(OccurrenceClaimOutcome.Claimed, first)

        // A second, concurrent trigger evaluating the same target day sees
        // AlreadyOwned and performs no side effect — same guarantee as the
        // morning digest, proven here for the weather-alert key shape too.
        val second = store.claim(weatherKey, "WEATHER_ALERT", LocalDate.of(2026, 9, 11), "PERIODIC_FALLBACK", now + 1_000)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.CLAIMED), second)

        store.markDeliveryAttempt(weatherKey)
        store.markDelivered(weatherKey)
        assertEquals(ProactiveOccurrenceState.DELIVERED.name, dao.rowOrNull(weatherKey)!!.state)

        // A later evaluation the same evening (e.g. the hourly re-check)
        // never redelivers — one occurrence per target day, regardless of
        // how many times the window re-evaluates.
        val third = store.claim(weatherKey, "WEATHER_ALERT", LocalDate.of(2026, 9, 11), "CONFIGURED_TIME", now + 3_600_000)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), third)
    }

    // --- § MICRO-PATCH 14.2.1 — making the configured briefing time
    // user-selectable must never let a changed schedule redeliver a
    // same-day occurrence. The occurrence identity (morningDigest(date)) is
    // date-only by construction — the scheduling/time layer never
    // participates in it (§6), so these two tests pin the mandatory
    // scenario from the spec directly instead of leaving it only implied
    // by the more general tests above. ---

    @Test
    fun `08_00 briefing already DELIVERED, user changes the setting to 09_00 - the next CONFIGURED_TIME firing at the new hour is suppressed, not redelivered`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)

        // The original 08:00 CONFIGURED_TIME firing claims and delivers today's digest.
        val original = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        assertEquals(OccurrenceClaimOutcome.Claimed, original)
        store.markDeliveryAttempt(key)
        store.markDelivered(key)
        assertEquals(ProactiveOccurrenceState.DELIVERED.name, dao.rowOrNull(key)!!.state)

        // The user then changes the setting to 09:00 (ProactiveSettingsViewModel.
        // setMorningBriefingTime -> MorningTriggerScheduler re-arms the SAME
        // KEY_CONFIGURED_TIME alarm for the new hour, but the occurrence KEY
        // computed by ProactiveOccurrenceKey.morningDigest is unaffected — it
        // is date-only, never a function of the configured hour/minute). The
        // re-armed alarm fires later the same day, still on the SAME logical
        // date, and reaches this same claim() call:
        val laterSameDay = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now + 3_600_000)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), laterSameDay)

        // No second delivery attempt was ever recorded.
        assertEquals(ProactiveOccurrenceState.DELIVERED.name, dao.rowOrNull(key)!!.state)
    }

    @Test
    fun `the occurrence key never encodes the configured hour or minute - two different configured times for the same date claim the exact same key`() = runTest {
        // ProactiveOccurrenceKey.morningDigest(date) (core) takes only a
        // LocalDate — this pins that a settings change can never fork the
        // occurrence identity in two, which would defeat the whole claim
        // mechanism by letting "08:00's occurrence" and "09:00's occurrence"
        // both exist and both deliver.
        val keyAt0800Setting = com.simone.jarvismobile.core.proactive.ProactiveOccurrenceKey.morningDigest(date)
        val keyAt0935Setting = com.simone.jarvismobile.core.proactive.ProactiveOccurrenceKey.morningDigest(date)
        assertEquals(keyAt0800Setting, keyAt0935Setting)
        assertEquals(key, keyAt0800Setting)
    }

    // ==================================================================
    // § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.2. Real
    // device failure: three Morning Briefings delivered the same morning
    // (08:00 no emoji, 08:14 with emoji, 09:00 a third). Root cause found
    // by code audit, not assumption: ProactiveManager.refreshMorningDigestNotification()
    // (called only by MorningRefreshWorker, +10min/+60min after a REAL
    // delivery) composed and posted a notification directly — the ONE
    // path in the whole app with occurrence claim = NO. Tests below cover
    // items 1-21 of the mission's required test list that are expressible
    // at this pure store level (no Android Context needed); items
    // touching ProactiveManager/ProactiveNotifier/MorningRefreshWorker
    // themselves are covered by MorningBriefingCanonicalGateRegressionTest.kt
    // (source-scan) and MorningRefreshGateTest.kt (:core, pure).
    // ==================================================================

    // --- peek(): read-only, never a claim, never mutates -----------------

    @Test
    fun `peek on an empty table returns null`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        assertNull(store.peek(key)?.state)
    }

    @Test
    fun `peek reflects the real current state and owning trigger without claiming`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        store.markDeliveryAttempt(key)
        store.markDelivered(key)

        val snapshot = store.peek(key)
        assertEquals(ProactiveOccurrenceState.DELIVERED, snapshot?.state)
        assertEquals("CONFIGURED_TIME", snapshot?.owningTriggerSource)
    }

    @Test
    fun `peek never mutates the row - repeated peeks are idempotent`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", now)
        val before = dao.rowOrNull(key)!!.copy()
        repeat(3) { store.peek(key) }
        assertEquals(before, dao.rowOrNull(key))
    }

    // --- item 1: CONFIGURED_TIME and NEXT_ALARM simultaneous -------------

    @Test
    fun `test item 1 - CONFIGURED_TIME and NEXT_ALARM claiming at the exact same instant - exactly one wins`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val a = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        val b = store.claim(key, "MORNING_DIGEST", date, "NEXT_ALARM", now)
        val outcomes = listOf(a, b)
        assertEquals(1, outcomes.count { it == OccurrenceClaimOutcome.Claimed })
        assertEquals(1, outcomes.count { it is OccurrenceClaimOutcome.AlreadyOwned })
    }

    // --- items 2/3: CONFIGURED_TIME then a later trigger, +14min/+60min --

    @Test
    fun `test item 2 - CONFIGURED_TIME delivers, FIRST_UNLOCK +14min later sees ALREADY_DELIVERED`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        store.markDeliveryAttempt(key)
        store.markDelivered(key)

        val fourteenMinLater = now + 14 * 60_000L
        val outcome = store.claim(key, "MORNING_DIGEST", date, "FIRST_UNLOCK", fourteenMinLater)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), outcome)
    }

    @Test
    fun `test item 3 - CONFIGURED_TIME delivers, PERIODIC_FALLBACK +60min later sees ALREADY_DELIVERED`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        store.markDeliveryAttempt(key)
        store.markDelivered(key)

        val sixtyMinLater = now + 60 * 60_000L
        val outcome = store.claim(key, "MORNING_DIGEST", date, "PERIODIC_FALLBACK", sixtyMinLater)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), outcome)
    }

    // --- item 4: four trigger sources sequentially over 90 minutes -------

    @Test
    fun `test item 4 - all four trigger sources sequentially over 90 minutes after delivery - none redeliver`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        store.markDeliveryAttempt(key)
        store.markDelivered(key)

        val laterSources = listOf("NEXT_ALARM", "FIRST_UNLOCK", "PERIODIC_FALLBACK", "MANUAL")
        laterSources.forEachIndexed { index, source ->
            val at = now + (index + 1) * 20 * 60_000L // spread across ~90 minutes
            val outcome = store.claim(key, "MORNING_DIGEST", date, source, at)
            assertEquals(
                "trigger source $source at +${(index + 1) * 20}min must see ALREADY_DELIVERED",
                OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED),
                outcome,
            )
        }
    }

    // --- item 5: all four trigger sources "concurrently" (same instant) --

    @Test
    fun `test item 5 - all four trigger sources at the exact same instant before any delivery - exactly one wins the claim`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        val sources = listOf("CONFIGURED_TIME", "NEXT_ALARM", "FIRST_UNLOCK", "PERIODIC_FALLBACK")
        val outcomes = sources.map { store.claim(key, "MORNING_DIGEST", date, it, now) }
        assertEquals(1, outcomes.count { it == OccurrenceClaimOutcome.Claimed })
        assertEquals(3, outcomes.count { it is OccurrenceClaimOutcome.AlreadyOwned })
        // Only one row exists, never a per-source fork.
        assertEquals(ProactiveOccurrenceState.CLAIMED.name, dao.rowOrNull(key)!!.state)
    }

    // --- items 6/7: process recreation / reboot between triggers ---------

    @Test
    fun `test item 6_7 - a new ProactiveOccurrenceStore instance over the SAME dao - simulating process restart or reboot - still honors DELIVERED`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val firstProcessStore = ProactiveOccurrenceStore(dao)
        firstProcessStore.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        firstProcessStore.markDeliveryAttempt(key)
        firstProcessStore.markDelivered(key)

        // A fresh Store instance (Hilt would construct a new @Singleton after
        // a process restart) wrapping the SAME underlying dao/row — exactly
        // what survives a real process kill or device reboot, since the
        // occurrence lives in Room, not in-memory.
        val secondProcessStore = ProactiveOccurrenceStore(dao)
        val outcome = secondProcessStore.claim(key, "MORNING_DIGEST", date, "NEXT_ALARM", now + 30 * 60_000L)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), outcome)
    }

    // --- items 10/11: stale/old scheduled callback fires after DELIVERED -

    @Test
    fun `test item 10_11 - a stale callback (old OR new exact alarm identity) firing after DELIVERED is always a harmless no-op`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)
        store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        store.markDeliveryAttempt(key)
        store.markDelivered(key)

        // MorningTriggerScheduler.scheduleConfiguredTimeTrigger() always
        // reschedules under the SAME PendingIntent key (KEY_CONFIGURED_TIME,
        // FLAG_UPDATE_CURRENT — verified by MorningTriggerSchedulerAlarmIdentityRegressionTest),
        // so "old and new exact alarms" can never both be independently
        // live — but even if a stale callback fired anyway (defense in
        // depth, § "persistent occurrence gate FIRST, scheduler
        // cancellation/replacement SECOND"), it reaches this SAME claim()
        // call and is suppressed identically regardless of which alarm
        // instance produced it.
        val staleCallback = store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now + 45 * 60_000L)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), staleCallback)
    }

    // --- item 13: DELIVERED is terminal for EVERY trigger source ---------

    @Test
    fun `test item 13 - DELIVERED is terminal for every trigger source, parametrized`() = runTest {
        val allSources = listOf(
            "NEXT_ALARM", "CONFIGURED_TIME", "FIRST_UNLOCK", "PERIODIC_FALLBACK",
            "MANUAL", "POST_BRIEFING_REFRESH_+10min", "POST_BRIEFING_REFRESH_+60min",
        )
        allSources.forEach { deliveringSource ->
            val dao = FakeProactiveOccurrenceDao()
            val store = ProactiveOccurrenceStore(dao)
            store.claim(key, "MORNING_DIGEST", date, deliveringSource, now)
            store.markDeliveryAttempt(key)
            store.markDelivered(key)

            allSources.forEach { laterSource ->
                val outcome = store.claim(key, "MORNING_DIGEST", date, laterSource, now + 60_000L)
                assertEquals(
                    "delivered by $deliveringSource, retried by $laterSource must still be terminal",
                    OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED),
                    outcome,
                )
            }
        }
    }

    // --- item 17: MorningRefreshWorker cannot create another briefing ----

    @Test
    fun `test item 17 - the refresh gate (peek + MorningRefreshGate) allows a silent refresh only once DELIVERED, never before`() = runTest {
        val dao = FakeProactiveOccurrenceDao()
        val store = ProactiveOccurrenceStore(dao)

        // Before any claim at all - MorningRefreshWorker firing on a day the
        // digest was never even attempted must be a pure no-op.
        assertFalse(
            com.simone.jarvismobile.core.proactive.MorningRefreshGate.shouldRefresh(store.peek(key)?.state),
        )

        store.claim(key, "MORNING_DIGEST", date, "CONFIGURED_TIME", now)
        // CLAIMED but not yet delivered (e.g. the +10min worker racing a
        // slow generation/notify) - still must not refresh.
        assertFalse(
            com.simone.jarvismobile.core.proactive.MorningRefreshGate.shouldRefresh(store.peek(key)?.state),
        )

        store.markDeliveryAttempt(key)
        // DELIVERY_PENDING - still not a real delivery yet.
        assertFalse(
            com.simone.jarvismobile.core.proactive.MorningRefreshGate.shouldRefresh(store.peek(key)?.state),
        )

        store.markDelivered(key)
        // Only now, genuinely DELIVERED, may a silent refresh proceed.
        assertTrue(
            com.simone.jarvismobile.core.proactive.MorningRefreshGate.shouldRefresh(store.peek(key)?.state),
        )
    }
}
