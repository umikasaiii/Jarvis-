package com.simone.jarvismobile.automation.rule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The aggregate rule that decides whether a completed occurrence's outcomes
 * durably prove COMMITTED / RETRYABLE_NO_EFFECT / INDETERMINATE (§ JARVIS
 * Implementation Master Plan PASSAGGIO 8.1 §3/§10 test items 4-9). Pure
 * Kotlin, no Android dependency — runs on the plain JVM.
 */
class OccurrenceCommitStateTest {

    @Test
    fun `all actions known successful is COMMITTED`() {
        val outcomes = listOf("SPEAK" to ActionOutcome.Done, "SHOW_NOTIFICATION" to ActionOutcome.Done)
        assertEquals(OccurrenceCommitState.COMMITTED, classifyOccurrenceCommitState(outcomes))
    }

    @Test
    fun `every action skipped or provably no-effect is RETRYABLE_NO_EFFECT`() {
        val outcomes = listOf(
            "SPEAK" to ActionOutcome.Skipped("dry-run"),
            "RUN_TOOL" to ActionOutcome.Failed("comando non riconosciuto", provenNoEffect = true),
        )
        assertEquals(OccurrenceCommitState.RETRYABLE_NO_EFFECT, classifyOccurrenceCommitState(outcomes))
    }

    @Test
    fun `a done action mixed with a failure is INDETERMINATE not retryable`() {
        // § the critical safety property: a retry re-runs EVERY action, so a
        // partial success must never be treated as safe to blindly repeat.
        val outcomes = listOf(
            "SPEAK" to ActionOutcome.Done,
            "RUN_TOOL" to ActionOutcome.Failed("errore"),
        )
        assertEquals(OccurrenceCommitState.INDETERMINATE, classifyOccurrenceCommitState(outcomes))
    }

    @Test
    fun `a done action mixed with a proven no-effect failure is still INDETERMINATE`() {
        // Even a *provably* no-effect failure does not make the WHOLE
        // occurrence retryable once something else already had a real effect
        // — retrying would still re-run (and duplicate) the Done action.
        val outcomes = listOf(
            "SPEAK" to ActionOutcome.Done,
            "RUN_TOOL" to ActionOutcome.Failed("comando mancante", provenNoEffect = true),
        )
        assertEquals(OccurrenceCommitState.INDETERMINATE, classifyOccurrenceCommitState(outcomes))
    }

    @Test
    fun `a timeout with no successes is INDETERMINATE, never automatically retryable`() {
        val outcomes = listOf("RUN_TOOL" to ActionOutcome.Failed("timeout"))
        assertEquals(OccurrenceCommitState.INDETERMINATE, classifyOccurrenceCommitState(outcomes))
    }

    @Test
    fun `an unhandled-exception failure with no successes is INDETERMINATE`() {
        val outcomes = listOf("SHOW_NOTIFICATION" to ActionOutcome.Failed("SecurityException"))
        assertEquals(OccurrenceCommitState.INDETERMINATE, classifyOccurrenceCommitState(outcomes))
    }

    @Test
    fun `a mix of proven no-effect and unproven failures with no successes is INDETERMINATE`() {
        // Not every failure in the occurrence is proven safe, so the whole
        // occurrence must not be classified as retryable.
        val outcomes = listOf(
            "RUN_TOOL" to ActionOutcome.Failed("comando mancante", provenNoEffect = true),
            "SHOW_NOTIFICATION" to ActionOutcome.Failed("timeout"),
        )
        assertEquals(OccurrenceCommitState.INDETERMINATE, classifyOccurrenceCommitState(outcomes))
    }

    @Test
    fun `all actions skipped with no failures at all is RETRYABLE_NO_EFFECT`() {
        val outcomes = listOf("SPEAK" to ActionOutcome.Skipped("dry-run"))
        assertEquals(OccurrenceCommitState.RETRYABLE_NO_EFFECT, classifyOccurrenceCommitState(outcomes))
    }

    @Test
    fun `an empty outcome list is never COMMITTED`() {
        assertEquals(OccurrenceCommitState.INDETERMINATE, classifyOccurrenceCommitState(emptyList()))
    }

    @Test
    fun `only RETRYABLE_NO_EFFECT differs from the blocking states - matches the DAO's blocking filter`() {
        // The durable dedup query (AutomationExecutionDao.countBlockingSince/
        // Ever) blocks on `commitState != 'RETRYABLE_NO_EFFECT'` — this pins
        // that COMMITTED and INDETERMINATE are exactly the two states that
        // condition excludes from "safe to retry".
        val blocking = OccurrenceCommitState.entries.filter { it != OccurrenceCommitState.RETRYABLE_NO_EFFECT }
        assertEquals(setOf(OccurrenceCommitState.COMMITTED, OccurrenceCommitState.INDETERMINATE), blocking.toSet())
    }
}
