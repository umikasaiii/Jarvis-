package com.simone.jarvismobile.audio

import com.simone.jarvismobile.core.voice.VoiceTurnFailureStage
import com.simone.jarvismobile.core.voice.VoiceTurnOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live Voice Phase 0.2 — [VoiceTurnDiagnosticsRecorder.markTtsPlaybackStarted]
 * has zero Android dependencies (same as the rest of this class), so it is
 * exercised here directly on the JVM rather than only through
 * [com.simone.jarvismobile.core.voice.VoiceTurnDiagnosticsTest]'s pure
 * model-level coverage — these tests pin the *wiring*: that a stale/late
 * call after a turn has already finished cannot mutate anything, that a
 * second observation for the same turn is ignored (only the first counts),
 * and that the raw mark ends up producing the same latency the pure
 * `compute()` math already guarantees.
 */
class VoiceTurnDiagnosticsRecorderTest {

    @Test
    fun `a playback-start call with no in-flight turn is a safe no-op`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        // No beginTurn() call at all — nothing to mutate, must not throw.
        recorder.markTtsPlaybackStarted()
        assertEquals(0, recorder.history.value.size)
    }

    @Test
    fun `a late playback-start call after the turn already finished never mutates the finished record`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markTtsRequested()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val before = recorder.history.value.single()
        assertNull(before.ttsPlaybackStartLatencyMs)

        // A stale event arriving after finish() — must not throw, and must
        // not retroactively change the already-published record.
        recorder.markTtsPlaybackStarted()

        val after = recorder.history.value.single()
        assertEquals(before, after)
        assertNull(after.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `only the first playback-start observation is kept for a turn`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markTtsRequested()
        recorder.markTtsPlaybackStarted()
        // A short, real wait so a second (wrongly-accepted) observation
        // would measurably move the latency forward if it were not ignored.
        Thread.sleep(60)
        recorder.markTtsPlaybackStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertTrue(
            "second observation must be ignored, but latency was ${record.ttsPlaybackStartLatencyMs}ms",
            (record.ttsPlaybackStartLatencyMs ?: Long.MAX_VALUE) < 60,
        )
    }

    @Test
    fun `a matching request-then-playback-start pair produces a real latency end to end`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markTtsRequested()
        Thread.sleep(10)
        recorder.markTtsPlaybackStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        val latency = record.ttsPlaybackStartLatencyMs
        assertTrue("expected a non-null, non-negative latency, got $latency", latency != null && latency >= 0)
    }

    @Test
    fun `playback-start observed without a preceding request mark never fabricates a latency`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        // No markTtsRequested() at all — mirrors an unmarked speakOut() call
        // (e.g. an error-message utterance outside the tracked answer path).
        recorder.markTtsPlaybackStarted()
        recorder.finish(VoiceTurnOutcome.NO_SPEECH, VoiceTurnFailureStage.STT)

        val record = recorder.history.value.single()
        assertNull(record.ttsPlaybackStartLatencyMs)
    }

    @Test
    fun `a new turn after a finished one starts with no playback-start evidence of its own`() {
        val recorder = VoiceTurnDiagnosticsRecorder()

        recorder.beginTurn(followUpIndex = 0)
        recorder.markTtsRequested()
        recorder.markTtsPlaybackStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        recorder.beginTurn(followUpIndex = 1)
        recorder.markTtsRequested()
        // No markTtsPlaybackStarted() for this second turn.
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val turns = recorder.history.value
        assertEquals(2, turns.size)
        assertNull(turns[1].ttsPlaybackStartLatencyMs)
    }

    // --- Live Voice Phase 0.3 — markUserSpeechStarted/markUserSpeechEnded --

    @Test
    fun `speech-boundary calls with no in-flight turn are a safe no-op`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.markUserSpeechStarted()
        recorder.markUserSpeechEnded()
        assertEquals(0, recorder.history.value.size)
    }

    @Test
    fun `ordered start and end for the same turn produce a real userSpeechDurationMs`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markUserSpeechStarted()
        Thread.sleep(10)
        recorder.markUserSpeechEnded()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        val duration = record.userSpeechDurationMs
        assertTrue("expected a non-null, non-negative duration, got $duration", duration != null && duration >= 0)
    }

    @Test
    fun `only the first start and first end observation are kept for a turn`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markUserSpeechStarted()
        recorder.markUserSpeechEnded()
        // A second, wrongly-accepted pair of observations would measurably
        // move the duration forward if it were not ignored.
        Thread.sleep(60)
        recorder.markUserSpeechStarted()
        recorder.markUserSpeechEnded()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertTrue(
            "second observations must be ignored, but duration was ${record.userSpeechDurationMs}ms",
            (record.userSpeechDurationMs ?: Long.MAX_VALUE) < 60,
        )
    }

    @Test
    fun `a late speech-boundary call after the turn already finished never mutates the finished record`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markUserSpeechStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val before = recorder.history.value.single()
        assertNull(before.userSpeechDurationMs)

        // A stale event arriving after finish() — must not throw, and must
        // not retroactively change the already-published record.
        recorder.markUserSpeechEnded()

        val after = recorder.history.value.single()
        assertEquals(before, after)
        assertNull(after.userSpeechDurationMs)
    }

    @Test
    fun `speech-end without a preceding speech-start never fabricates a duration`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        // No markUserSpeechStarted() at all.
        recorder.markUserSpeechEnded()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertNull(record.userSpeechDurationMs)
        // But the raw speech-end mark itself is still real evidence, usable
        // for sttFinalizationAfterSpeechMs/responsePlaybackAfterSpeechMs.
        recorder.markSttFinal()
    }

    @Test
    fun `speech-end feeds sttFinalizationAfterSpeechMs end to end through the recorder`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markUserSpeechStarted()
        recorder.markUserSpeechEnded()
        Thread.sleep(10)
        recorder.markSttFinal()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        val finalization = record.sttFinalizationAfterSpeechMs
        assertTrue("expected a non-null, non-negative value, got $finalization", finalization != null && finalization >= 0)
    }

    @Test
    fun `speech-end feeds responsePlaybackAfterSpeechMs end to end through the recorder`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markUserSpeechStarted()
        recorder.markUserSpeechEnded()
        recorder.markSttFinal()
        recorder.markAnswerStarted()
        recorder.markAnswerReady()
        recorder.markTtsRequested()
        Thread.sleep(10)
        recorder.markTtsPlaybackStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        val playback = record.responsePlaybackAfterSpeechMs
        assertTrue("expected a non-null, non-negative value, got $playback", playback != null && playback >= 0)
    }

    @Test
    fun `a new turn after a finished one starts with no speech-boundary evidence of its own`() {
        val recorder = VoiceTurnDiagnosticsRecorder()

        recorder.beginTurn(followUpIndex = 0)
        recorder.markUserSpeechStarted()
        recorder.markUserSpeechEnded()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        recorder.beginTurn(followUpIndex = 1)
        // No markUserSpeechStarted()/markUserSpeechEnded() for this second turn.
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val turns = recorder.history.value
        assertEquals(2, turns.size)
        assertNull(turns[1].userSpeechDurationMs)
    }
}
