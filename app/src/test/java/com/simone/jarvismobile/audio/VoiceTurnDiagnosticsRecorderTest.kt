package com.simone.jarvismobile.audio

import com.simone.jarvismobile.core.voice.ObservedAudioFocusState
import com.simone.jarvismobile.core.voice.ObservedAudioRoute
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

    // --- Live Voice Phase 0.4 — audio route + audio focus causal observability --

    @Test
    fun `a route observation with no in-flight turn is a safe no-op`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.markAudioRouteObservation(ObservedAudioRoute.PHONE, ObservedAudioRoute.PHONE, false)
        assertEquals(0, recorder.history.value.size)
    }

    @Test
    fun `a focus observation with no in-flight turn is a safe no-op`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.markAudioFocusObservation(ObservedAudioFocusState.GRANTED)
        assertEquals(0, recorder.history.value.size)
    }

    @Test
    fun `the first route observation of a turn becomes both initial and final, never a fabricated change`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markAudioRouteObservation(ObservedAudioRoute.PHONE, ObservedAudioRoute.SPEAKER, true)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertEquals(ObservedAudioRoute.PHONE, record.initialInputRoute)
        assertEquals(ObservedAudioRoute.PHONE, record.finalInputRoute)
        assertEquals(ObservedAudioRoute.SPEAKER, record.initialOutputRoute)
        assertEquals(ObservedAudioRoute.SPEAKER, record.finalOutputRoute)
        assertEquals(true, record.communicationRouteAppliedAtStart)
        assertEquals(false, record.routeChangedDuringTurn)
        assertEquals(0, record.routeChangeCount)
    }

    @Test
    fun `a genuine real route observation change bumps routeChangeCount and updates final`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markAudioRouteObservation(ObservedAudioRoute.PHONE, ObservedAudioRoute.PHONE, false)
        recorder.markAudioRouteObservation(ObservedAudioRoute.BLUETOOTH_HEADSET, ObservedAudioRoute.PHONE, false)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertEquals(ObservedAudioRoute.PHONE, record.initialInputRoute)
        assertEquals(ObservedAudioRoute.BLUETOOTH_HEADSET, record.finalInputRoute)
        assertEquals(true, record.routeChangedDuringTurn)
        assertEquals(1, record.routeChangeCount)
        assertEquals(true, record.bluetoothInputObserved)
    }

    @Test
    fun `a duplicate route observation reporting the same value never fabricates a second change`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markAudioRouteObservation(ObservedAudioRoute.PHONE, ObservedAudioRoute.PHONE, false)
        recorder.markAudioRouteObservation(ObservedAudioRoute.PHONE, ObservedAudioRoute.PHONE, false) // duplicate callback, same real state
        recorder.markAudioRouteObservation(ObservedAudioRoute.PHONE, ObservedAudioRoute.PHONE, false) // another duplicate
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertEquals(0, record.routeChangeCount)
        assertEquals(false, record.routeChangedDuringTurn)
    }

    @Test
    fun `a late route observation after the turn already finished never mutates the finished record`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markAudioRouteObservation(ObservedAudioRoute.PHONE, ObservedAudioRoute.PHONE, false)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val before = recorder.history.value.single()
        recorder.markAudioRouteObservation(ObservedAudioRoute.BLUETOOTH_HEADSET, ObservedAudioRoute.BLUETOOTH_HEADSET, true)

        val after = recorder.history.value.single()
        assertEquals(before, after)
        assertEquals(0, after.routeChangeCount)
    }

    @Test
    fun `initial focus granted with no later event is represented honestly through the recorder`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.GRANTED)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertEquals(ObservedAudioFocusState.GRANTED, record.audioFocusAtStart)
        assertEquals(false, record.audioFocusLostDuringTurn)
        assertEquals(false, record.audioFocusRegainedDuringTurn)
        assertEquals(0, record.focusChangeCount)
    }

    @Test
    fun `a real focus loss then a real gain is recorded as lost and regained with a correct final state`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.GRANTED)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.LOST_TRANSIENT)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.GAIN)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertEquals(true, record.audioFocusLostDuringTurn)
        assertEquals(true, record.audioFocusRegainedDuringTurn)
        assertEquals(2, record.focusChangeCount)
    }

    @Test
    fun `a gain with no prior loss is never counted as a regain`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.GRANTED)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.GAIN) // no loss ever observed first
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertEquals(false, record.audioFocusLostDuringTurn)
        assertEquals(false, record.audioFocusRegainedDuringTurn)
    }

    @Test
    fun `a cancellation co-occurring with a real focus loss stays CANCELLED through the recorder, never reclassified`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.GRANTED)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.LOST_PERMANENT)
        recorder.markCancellationRequested()
        recorder.finishCancelled()

        val record = recorder.history.value.single()
        assertEquals(VoiceTurnOutcome.CANCELLED, record.outcome)
        assertEquals(true, record.audioFocusLostDuringTurn)
    }

    @Test
    fun `routeChangeCount and focusChangeCount are bounded and never grow without limit`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        var route = ObservedAudioRoute.PHONE
        repeat(200) {
            route = if (route == ObservedAudioRoute.PHONE) ObservedAudioRoute.BLUETOOTH_HEADSET else ObservedAudioRoute.PHONE
            recorder.markAudioRouteObservation(route, route, false)
        }
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertTrue("expected routeChangeCount to be bounded, got ${record.routeChangeCount}", record.routeChangeCount <= 50)
    }

    @Test
    fun `a new turn after a finished one starts with entirely clean route and focus evidence`() {
        val recorder = VoiceTurnDiagnosticsRecorder()

        recorder.beginTurn(followUpIndex = 0)
        recorder.markAudioRouteObservation(ObservedAudioRoute.BLUETOOTH_HEADSET, ObservedAudioRoute.BLUETOOTH_HEADSET, true)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.GRANTED)
        recorder.markAudioFocusObservation(ObservedAudioFocusState.LOST_PERMANENT)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        recorder.beginTurn(followUpIndex = 1)
        // No route/focus marks at all for this second turn.
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val second = recorder.history.value[1]
        assertEquals(ObservedAudioRoute.NOT_AVAILABLE, second.initialInputRoute)
        assertEquals(ObservedAudioFocusState.NOT_AVAILABLE, second.audioFocusAtStart)
        assertEquals(false, second.audioFocusLostDuringTurn)
        assertEquals(0, second.routeChangeCount)
    }

    // --- Live Voice Phase 0.5 — markSttReady/markSttAttemptCount ----------

    @Test
    fun `a ready call with no in-flight turn is a safe no-op`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.markSttReady()
        assertEquals(0, recorder.history.value.size)
    }

    @Test
    fun `an attempt-count call with no in-flight turn is a safe no-op`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.markSttAttemptCount(2)
        assertEquals(0, recorder.history.value.size)
    }

    @Test
    fun `a matching stt-started-then-ready pair produces a real non-negative sttReadyLatencyMs end to end`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        Thread.sleep(10)
        recorder.markSttReady()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        val latency = record.sttReadyLatencyMs
        assertTrue("expected a non-null, non-negative latency, got $latency", latency != null && latency >= 0)
    }

    @Test
    fun `ready observed without a preceding stt-started mark never fabricates a latency`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        // No markSttStarted() at all.
        recorder.markSttReady()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertNull(record.sttReadyLatencyMs)
    }

    @Test
    fun `only the first ready observation is kept for a turn`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markSttReady()
        // A second (wrongly-accepted) observation would measurably move the
        // latency forward if it were not ignored.
        Thread.sleep(60)
        recorder.markSttReady()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertTrue(
            "second observation must be ignored, but latency was ${record.sttReadyLatencyMs}ms",
            (record.sttReadyLatencyMs ?: Long.MAX_VALUE) < 60,
        )
    }

    @Test
    fun `a late ready call after the turn already finished never mutates the finished record`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val before = recorder.history.value.single()
        assertNull(before.sttReadyLatencyMs)

        // A stale event arriving after finish() — must not throw, and must
        // not retroactively change the already-published record.
        recorder.markSttReady()

        val after = recorder.history.value.single()
        assertEquals(before, after)
        assertNull(after.sttReadyLatencyMs)
    }

    @Test
    fun `ready feeds speechStartAfterReadyMs end to end through the recorder`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markSttReady()
        Thread.sleep(10)
        recorder.markUserSpeechStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        val gap = record.speechStartAfterReadyMs
        assertTrue("expected a non-null, non-negative value, got $gap", gap != null && gap >= 0)
    }

    @Test
    fun `attempt count observed through the recorder end to end`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttAttemptCount(3)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertEquals(3, record.sttAttemptCount)
        assertEquals(true, record.sttColdStartRetryObserved)
    }

    @Test
    fun `only the first attempt-count observation is kept for a turn`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttAttemptCount(1)
        // A second, wrongly-accepted summary must be ignored.
        recorder.markSttAttemptCount(5)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertEquals(1, record.sttAttemptCount)
        assertEquals(false, record.sttColdStartRetryObserved)
    }

    @Test
    fun `a late attempt-count call after the turn already finished never mutates the finished record`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val before = recorder.history.value.single()
        assertNull(before.sttAttemptCount)

        recorder.markSttAttemptCount(2)

        val after = recorder.history.value.single()
        assertEquals(before, after)
        assertNull(after.sttAttemptCount)
    }

    @Test
    fun `a cancelled turn keeps the CANCELLED outcome regardless of ready and attempt evidence`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markSttReady()
        recorder.markSttAttemptCount(2)
        recorder.markCancellationRequested()
        recorder.finishCancelled()

        val record = recorder.history.value.single()
        assertEquals(VoiceTurnOutcome.CANCELLED, record.outcome)
    }

    @Test
    fun `wake-word recognizer events never reach this recorder -- there is no subscriber wired to a second instance`() {
        // RecognizerWakeWordEngine owns its own, entirely separate
        // AndroidOnDeviceSpeechEngine instance and never subscribes this
        // recorder to it (see SessionCoordinator's own doc comment) --
        // this test documents that guarantee at the recorder level: a
        // fresh turn with no marks at all has no ready/attempt evidence,
        // exactly as if no wake-word activity could ever leak in.
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertNull(record.sttReadyLatencyMs)
        assertNull(record.sttAttemptCount)
    }

    @Test
    fun `a new turn after a finished one starts with no ready or attempt evidence of its own`() {
        val recorder = VoiceTurnDiagnosticsRecorder()

        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markSttReady()
        recorder.markSttAttemptCount(3)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        recorder.beginTurn(followUpIndex = 1)
        // No markSttReady()/markSttAttemptCount() for this second turn.
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val turns = recorder.history.value
        assertEquals(2, turns.size)
        assertNull(turns[1].sttReadyLatencyMs)
        assertNull(turns[1].sttAttemptCount)
    }

    // --- Live Voice Phase 0.6 — markFirstPartialObserved -------------------

    @Test
    fun `a first-partial call with no in-flight turn is a safe no-op`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.markFirstPartialObserved()
        assertEquals(0, recorder.history.value.size)
    }

    @Test
    fun `a matching stt-started-then-partial pair produces a real non-negative partialTranscriptLatencyMs`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        Thread.sleep(10)
        recorder.markFirstPartialObserved()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val latency = recorder.history.value.single().partialTranscriptLatencyMs
        assertTrue("expected non-negative latency, got $latency", latency != null && latency >= 0)
    }

    @Test
    fun `no partial observed yields null even when the turn completes normally`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markSttFinal()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val record = recorder.history.value.single()
        assertNull(record.partialTranscriptLatencyMs)
        assertNull(record.partialAfterSpeechStartMs)
    }

    @Test
    fun `second and third partial observations never overwrite the first`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markFirstPartialObserved()
        Thread.sleep(60)
        recorder.markFirstPartialObserved()
        recorder.markFirstPartialObserved()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val latency = recorder.history.value.single().partialTranscriptLatencyMs
        assertTrue("later partials must be ignored, latency was ${latency}ms", (latency ?: Long.MAX_VALUE) < 60)
    }

    @Test
    fun `a late partial call after the turn already finished never mutates the finished record`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)
        val before = recorder.history.value.single()

        recorder.markFirstPartialObserved()

        assertEquals(before, recorder.history.value.single())
        assertNull(recorder.history.value.single().partialTranscriptLatencyMs)
    }

    @Test
    fun `partial after speech start feeds partialAfterSpeechStartMs end to end`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markUserSpeechStarted()
        Thread.sleep(10)
        recorder.markFirstPartialObserved()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val gap = recorder.history.value.single().partialAfterSpeechStartMs
        assertTrue("expected non-negative gap, got $gap", gap != null && gap >= 0)
    }

    @Test
    fun `a cancelled turn keeps the CANCELLED outcome regardless of partial evidence`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markFirstPartialObserved()
        recorder.markCancellationRequested()
        recorder.finishCancelled()

        assertEquals(VoiceTurnOutcome.CANCELLED, recorder.history.value.single().outcome)
    }

    @Test
    fun `a new turn after a finished one starts with no partial evidence of its own`() {
        val recorder = VoiceTurnDiagnosticsRecorder()
        recorder.beginTurn(followUpIndex = 0)
        recorder.markSttStarted()
        recorder.markFirstPartialObserved()
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        recorder.beginTurn(followUpIndex = 1)
        recorder.finish(VoiceTurnOutcome.COMPLETED, VoiceTurnFailureStage.NONE)

        val turns = recorder.history.value
        assertEquals(2, turns.size)
        assertNull(turns[1].partialTranscriptLatencyMs)
        assertNull(turns[1].partialAfterSpeechStartMs)
    }
}
