package com.simone.jarvismobile.core.session

import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.core.state.ConversationStateMachine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Fase1FlowTest {

    /** Configurable fake environment that records which steps ran. */
    private class FakeEnv(
        var prepare: PrepareOutcome = PrepareOutcome.Ready(AudioKind.AIRPODS, fellBackToPhone = false),
        var record: RecordOutcome = RecordOutcome.Completed,
        var ttsReady: Boolean = true,
        var speak: SpeakOutcome = SpeakOutcome.Done,
    ) : Fase1Environment {
        var recordCalled = false
        var speakCalled = false
        var endSessionCalled = false

        override suspend fun prepareAudio() = prepare
        override suspend fun record(): RecordOutcome { recordCalled = true; return record }
        override suspend fun ensureTtsReady() = ttsReady
        override suspend fun speak(): SpeakOutcome { speakCalled = true; return speak }
        override suspend fun endSession() { endSessionCalled = true }
    }

    private fun flow(env: FakeEnv) = Fase1Flow(ConversationStateMachine(), env)

    @Test
    fun `happy path with airpods reaches idle and speaks`() = runTest {
        val env = FakeEnv()
        val end = flow(env).run()
        assertEquals(ConversationState.Idle, end)
        assertTrue(env.recordCalled)
        assertTrue(env.speakCalled)
        assertTrue(env.endSessionCalled)
    }

    @Test
    fun `recording timeout is a normal end and still speaks`() = runTest {
        val env = FakeEnv(record = RecordOutcome.Timeout)
        val end = flow(env).run()
        assertEquals(ConversationState.Idle, end)
        assertTrue(env.speakCalled)
    }

    @Test
    fun `permission denied stops before recording`() = runTest {
        val env = FakeEnv(prepare = PrepareOutcome.PermissionDenied)
        val end = flow(env).run()
        assertEquals(ConversationState.PermissionRequired, end)
        assertTrue(!env.recordCalled)
        assertTrue(env.endSessionCalled)
    }

    @Test
    fun `phone fallback still completes the session`() = runTest {
        val env = FakeEnv(prepare = PrepareOutcome.Ready(AudioKind.PHONE, fellBackToPhone = true))
        val end = flow(env).run()
        assertEquals(ConversationState.Idle, end)
        assertTrue(env.speakCalled)
    }

    @Test
    fun `bluetooth unavailable at prepare surfaces bluetooth state`() = runTest {
        val env = FakeEnv(prepare = PrepareOutcome.BluetoothUnavailable)
        val end = flow(env).run()
        assertEquals(ConversationState.BluetoothUnavailable, end)
        assertTrue(!env.recordCalled)
    }

    @Test
    fun `bluetooth lost during recording surfaces bluetooth state`() = runTest {
        val env = FakeEnv(record = RecordOutcome.BluetoothLost)
        val end = flow(env).run()
        assertEquals(ConversationState.BluetoothUnavailable, end)
        assertTrue(!env.speakCalled)
    }

    @Test
    fun `audio focus lost during recording is recoverable`() = runTest {
        val env = FakeEnv(record = RecordOutcome.FocusLost)
        val end = flow(env).run()
        assertTrue(end is ConversationState.RecoverableError)
        assertEquals("audio_focus_lost", (end as ConversationState.RecoverableError).code)
        assertTrue(env.endSessionCalled)
    }

    @Test
    fun `cancel during recording ends cancelled`() = runTest {
        val env = FakeEnv(record = RecordOutcome.Cancelled)
        val end = flow(env).run()
        assertEquals(ConversationState.Cancelled, end)
        assertTrue(!env.speakCalled)
    }

    @Test
    fun `tts unavailable is recoverable and does not speak`() = runTest {
        val env = FakeEnv(ttsReady = false)
        val end = flow(env).run()
        assertTrue(end is ConversationState.RecoverableError)
        assertEquals("tts_unavailable", (end as ConversationState.RecoverableError).code)
        assertTrue(!env.speakCalled)
        assertTrue(env.endSessionCalled)
    }

    @Test
    fun `tts error during speaking is recoverable`() = runTest {
        val env = FakeEnv(speak = SpeakOutcome.Failure("boom"))
        val end = flow(env).run()
        assertTrue(end is ConversationState.RecoverableError)
        assertTrue(env.endSessionCalled)
    }

    @Test
    fun `cancel during speaking ends cancelled`() = runTest {
        val env = FakeEnv(speak = SpeakOutcome.Cancelled)
        val end = flow(env).run()
        assertEquals(ConversationState.Cancelled, end)
    }
}
