package com.simone.jarvismobile.core.voice

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live Voice Phase 0.6 — the pure per-callback decision for onPartialResults(). */
class SttPartialPolicyTest {

    @Test
    fun `a current-generation non-blank partial updates the UI and emits one observation`() {
        val d = SttPartialPolicy.decide(true, "ciao", alreadyObservedThisAttempt = false)
        assertEquals("ciao", d.updateUiWith)
        assertTrue(d.emitObserved)
    }

    @Test
    fun `a second partial of the same attempt updates the UI but never emits another observation`() {
        val d = SttPartialPolicy.decide(true, "ciao come", alreadyObservedThisAttempt = true)
        assertEquals("ciao come", d.updateUiWith)
        assertFalse(d.emitObserved)
    }

    @Test
    fun `a stale-generation partial can neither overwrite the UI text nor become evidence`() {
        val d = SttPartialPolicy.decide(false, "vecchio", alreadyObservedThisAttempt = false)
        assertNull(d.updateUiWith)
        assertFalse(d.emitObserved)
    }

    @Test
    fun `a blank partial is not evidence but keeps the pre-existing UI behavior`() {
        val d = SttPartialPolicy.decide(true, "  ", alreadyObservedThisAttempt = false)
        assertEquals("  ", d.updateUiWith)
        assertFalse(d.emitObserved)
    }

    @Test
    fun `a missing partial bundle does nothing`() {
        val d = SttPartialPolicy.decide(true, null, alreadyObservedThisAttempt = false)
        assertNull(d.updateUiWith)
        assertFalse(d.emitObserved)
    }

    @Test
    fun `retry scenario with the real generation counter rejects the old attempt and accepts the current one`() {
        val generation = AtomicLong(0L)
        val attempt1 = generation.incrementAndGet()   // abandoned retry attempt
        val attempt2 = generation.incrementAndGet()   // current attempt

        // A late partial from attempt 1 arrives after attempt 2 began.
        val stale = SttPartialPolicy.decide(attempt1 == generation.get(), "stale", false)
        assertNull(stale.updateUiWith)
        assertFalse(stale.emitObserved)

        // A partial from the current attempt is accepted.
        val live = SttPartialPolicy.decide(attempt2 == generation.get(), "live", false)
        assertEquals("live", live.updateUiWith)
        assertTrue(live.emitObserved)
    }

    @Test
    fun `an abandoned generation cannot overwrite the active partial UI state`() {
        val generation = AtomicLong(0L)
        var ui = ""
        val old = generation.incrementAndGet()
        val current = generation.incrementAndGet()
        SttPartialPolicy.decide(current == generation.get(), "attivo", false).updateUiWith?.let { ui = it }
        SttPartialPolicy.decide(old == generation.get(), "scartato", false).updateUiWith?.let { ui = it }
        assertEquals("attivo", ui)
    }
}
