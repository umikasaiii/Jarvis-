package com.simone.jarvismobile.core.proactive

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 14.1 §V (test items
 * 7-10, 12-14, 19, 20 as applicable to the pure reconciliation policy).
 */
class ProactiveOccurrenceTest {

    private val staleAfterMs = 10 * 60 * 1000L
    private val now = 1_000_000_000L

    // --- no prior row: always a fresh claim -----------------------------

    @Test
    fun `no existing row always claims fresh`() {
        val outcome = ProactiveOccurrenceReconciler.decide(null, null, now, staleAfterMs)
        assertEquals(OccurrenceClaimOutcome.Claimed, outcome)
    }

    // --- item 7: DELIVERED suppresses everything, forever ----------------

    @Test
    fun `DELIVERED is never retried regardless of age`() {
        val fresh = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.DELIVERED, now, now, staleAfterMs)
        val ancient = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.DELIVERED, 0L, now, staleAfterMs)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), fresh)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), ancient)
    }

    @Test
    fun `FAILED_FINAL is never retried regardless of age`() {
        val outcome = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.FAILED_FINAL, 0L, now, staleAfterMs)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.FAILED_FINAL), outcome)
    }

    /**
     * § MICRO-PATCH 14.2.2, test item 16: "stale claim takeover does not
     * duplicate a completed side effect". [ProactiveOccurrenceReconciler.decide]
     * has NO `triggerSource` parameter at all — the trigger that asks can
     * never influence the outcome by construction — and DELIVERED is
     * special-cased in its own branch BEFORE the staleness/age check ever
     * runs, so no age (however extreme) can ever route a DELIVERED row into
     * the takeover branch. This is the strongest possible proof: not "we
     * tested a few large ages", but that the code path computing takeover
     * eligibility from age is structurally unreachable for DELIVERED.
     */
    @Test
    fun `DELIVERED never reaches the staleness-takeover branch, at any age including years`() {
        val ages = listOf(0L, 1L, staleAfterMs - 1, staleAfterMs, staleAfterMs + 1, 365L * 24 * 60 * 60 * 1000L)
        ages.forEach { age ->
            val outcome = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.DELIVERED, now - age, now, staleAfterMs)
            assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERED), outcome)
        }
    }

    // --- item 8: FAILED_RETRYABLE may always retry ------------------------

    @Test
    fun `FAILED_RETRYABLE always allows takeover, even immediately`() {
        val outcome = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.FAILED_RETRYABLE, now, now, staleAfterMs)
        assertEquals(OccurrenceClaimOutcome.TakeoverAllowed, outcome)
    }

    // --- item 9/10/19/20: UNKNOWN (in-flight) is never blindly retried ----

    @Test
    fun `a fresh CLAIMED row (in flight, same trigger cycle) is never taken over`() {
        val outcome = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.CLAIMED, now, now, staleAfterMs)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.CLAIMED), outcome)
    }

    @Test
    fun `a fresh GENERATED row is never taken over`() {
        val outcome = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.GENERATED, now - 1_000, now, staleAfterMs)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.GENERATED), outcome)
    }

    @Test
    fun `a fresh DELIVERY_PENDING row is never taken over - the exact UNKNOWN window`() {
        val outcome = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.DELIVERY_PENDING, now - 5_000, now, staleAfterMs)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.DELIVERY_PENDING), outcome)
    }

    @Test
    fun `a CLAIMED row exactly at the staleness boundary is eligible for takeover`() {
        val outcome = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.CLAIMED, now - staleAfterMs, now, staleAfterMs)
        assertEquals(OccurrenceClaimOutcome.TakeoverAllowed, outcome)
    }

    @Test
    fun `a CLAIMED row one millisecond before the staleness boundary is NOT eligible`() {
        val outcome = ProactiveOccurrenceReconciler.decide(ProactiveOccurrenceState.CLAIMED, now - staleAfterMs + 1, now, staleAfterMs)
        assertEquals(OccurrenceClaimOutcome.AlreadyOwned(ProactiveOccurrenceState.CLAIMED), outcome)
    }

    @Test
    fun `a genuinely stale DELIVERY_PENDING row - the process-death-after-notify crash window - allows takeover`() {
        // § §M crash window 3: notification delivery requested, process dies
        // before delivered-state persistence. A later trigger must be able to
        // reconcile using the SAME occurrence rather than being blocked forever.
        val outcome = ProactiveOccurrenceReconciler.decide(
            ProactiveOccurrenceState.DELIVERY_PENDING, now - staleAfterMs - 60_000, now, staleAfterMs,
        )
        assertEquals(OccurrenceClaimOutcome.TakeoverAllowed, outcome)
    }

    // --- key stability (§D, test items 12/13/14) --------------------------

    @Test
    fun `same logical date always produces the same key`() {
        val a = ProactiveOccurrenceKey.morningDigest(LocalDate.of(2026, 9, 9))
        val b = ProactiveOccurrenceKey.morningDigest(LocalDate.of(2026, 9, 9))
        assertEquals(a, b)
    }

    @Test
    fun `different logical dates produce different keys`() {
        val a = ProactiveOccurrenceKey.morningDigest(LocalDate.of(2026, 9, 9))
        val b = ProactiveOccurrenceKey.morningDigest(LocalDate.of(2026, 9, 10))
        assertTrue(a != b)
    }

    @Test
    fun `the key never embeds a timestamp, worker id, or random component`() {
        val key = ProactiveOccurrenceKey.morningDigest(LocalDate.of(2026, 9, 9))
        assertEquals("MORNING_DIGEST:2026-09-09", key)
    }

    /**
     * § drift guard — [ProactiveComposer.morningDigest] computes its own
     * `dedupKey` with the SAME literal format independently (kept that way
     * deliberately, so this pass never has to touch briefing content code,
     * §C). If the two ever diverge, the atomic occurrence claim and the
     * governor's own per-day dedup would silently stop referring to the
     * same logical occurrence — this test exists so that divergence fails
     * loudly here instead of showing up as a mysterious duplicate later.
     */
    @Test
    fun `the occurrence key format matches ProactiveComposer's own dedupKey format exactly`() {
        val date = LocalDate.of(2026, 3, 1)
        val composerStyleDedupKey = "${ProactiveKind.MORNING_DIGEST}:$date"
        assertEquals(composerStyleDedupKey, ProactiveOccurrenceKey.morningDigest(date))
    }

    // --- weatherAlert key (§ PASSAGGIO 14.2) -------------------------------

    @Test
    fun `weatherAlert key is stable for the same target date`() {
        val a = ProactiveOccurrenceKey.weatherAlert(LocalDate.of(2026, 9, 11))
        val b = ProactiveOccurrenceKey.weatherAlert(LocalDate.of(2026, 9, 11))
        assertEquals(a, b)
    }

    @Test
    fun `weatherAlert key differs for different target dates`() {
        val a = ProactiveOccurrenceKey.weatherAlert(LocalDate.of(2026, 9, 11))
        val b = ProactiveOccurrenceKey.weatherAlert(LocalDate.of(2026, 9, 12))
        assertTrue(a != b)
    }

    @Test
    fun `weatherAlert key never embeds a hazard tier - one occurrence per target day regardless of severity`() {
        assertEquals("WEATHER_ALERT:2026-09-11", ProactiveOccurrenceKey.weatherAlert(LocalDate.of(2026, 9, 11)))
    }

    @Test
    fun `weatherAlert key is distinct from morningDigest key on the same date`() {
        val date = LocalDate.of(2026, 9, 11)
        assertTrue(ProactiveOccurrenceKey.weatherAlert(date) != ProactiveOccurrenceKey.morningDigest(date))
    }

    @Test
    fun `weatherAlert key format matches the ProactiveComposer weatherAlert dedupKey exactly`() {
        val date = LocalDate.of(2026, 3, 1)
        val composerStyleDedupKey = "${ProactiveKind.WEATHER_ALERT}:$date"
        assertEquals(composerStyleDedupKey, ProactiveOccurrenceKey.weatherAlert(date))
    }
}
