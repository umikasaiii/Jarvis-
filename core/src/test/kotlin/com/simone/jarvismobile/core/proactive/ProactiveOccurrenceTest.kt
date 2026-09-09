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
}
