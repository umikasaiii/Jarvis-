package com.simone.jarvismobile.core.state

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationStateMachineTest {

    private fun machine() = ConversationStateMachine()

    @Test
    fun `happy path local reaches idle again`() {
        val m = machine()
        assertEquals(ConversationState.Idle, m.current)
        assertEquals(ConversationState.PreparingAudio, m.dispatch(ConversationEvent.StartRequested))
        assertEquals(ConversationState.Listening, m.dispatch(ConversationEvent.AudioReady))
        assertEquals(ConversationState.FinalizingSpeech, m.dispatch(ConversationEvent.SpeechEnded))
        assertEquals(ConversationState.Transcribing, m.dispatch(ConversationEvent.SpeechEnded))
        assertEquals(
            ConversationState.RetrievingMemory,
            m.dispatch(ConversationEvent.TranscriptReady("che ore sono")),
        )
        assertEquals(ConversationState.Routing, m.dispatch(ConversationEvent.MemoryRetrieved))
        assertEquals(ConversationState.ThinkingLocal, m.dispatch(ConversationEvent.Routed(RouteTarget.LOCAL)))
        assertEquals(ConversationState.Speaking, m.dispatch(ConversationEvent.AnswerReady))
        assertEquals(ConversationState.FollowUpWindow, m.dispatch(ConversationEvent.SpeechSynthesisFinished))
        assertEquals(ConversationState.Idle, m.dispatch(ConversationEvent.FollowUpTimeout))
    }

    @Test
    fun `remote routing goes through ThinkingRemote`() {
        val m = machine()
        drive(m, ConversationEvent.StartRequested, ConversationEvent.AudioReady, ConversationEvent.SpeechEnded)
        m.dispatch(ConversationEvent.SpeechEnded)
        m.dispatch(ConversationEvent.TranscriptReady("domanda complessa"))
        m.dispatch(ConversationEvent.MemoryRetrieved)
        assertEquals(ConversationState.ThinkingRemote, m.dispatch(ConversationEvent.Routed(RouteTarget.REMOTE_PC)))
    }

    @Test
    fun `remote failure falls back to local thinking`() {
        val m = machine()
        toState(m, ConversationState.ThinkingRemote)
        assertEquals(ConversationState.ThinkingLocal, m.dispatch(ConversationEvent.NetworkMissing))
    }

    @Test
    fun `cancel from any active state goes to Cancelled`() {
        val m = machine()
        toState(m, ConversationState.Listening)
        assertEquals(ConversationState.Cancelled, m.dispatch(ConversationEvent.CancelRequested))
        // and can restart
        assertEquals(ConversationState.PreparingAudio, m.dispatch(ConversationEvent.StartRequested))
    }

    @Test
    fun `cancel is ignored when already terminal`() {
        val m = machine()
        assertEquals(ConversationState.Idle, m.dispatch(ConversationEvent.CancelRequested))
    }

    @Test
    fun `barge in during speaking restarts capture`() {
        val m = machine()
        toState(m, ConversationState.Speaking)
        assertEquals(ConversationState.PreparingAudio, m.dispatch(ConversationEvent.BargeIn))
    }

    @Test
    fun `follow up speech restarts capture`() {
        val m = machine()
        toState(m, ConversationState.FollowUpWindow)
        assertEquals(ConversationState.PreparingAudio, m.dispatch(ConversationEvent.SpeechStarted))
    }

    @Test
    fun `sensitive tool requires confirmation and can be denied`() {
        val m = machine()
        toState(m, ConversationState.ThinkingLocal)
        assertEquals(ConversationState.AwaitingConfirmation, m.dispatch(ConversationEvent.ConfirmationRequired))
        assertEquals(ConversationState.Speaking, m.dispatch(ConversationEvent.ConfirmationDenied))
    }

    @Test
    fun `granted confirmation executes tool then speaks`() {
        val m = machine()
        toState(m, ConversationState.AwaitingConfirmation)
        assertEquals(ConversationState.ExecutingTool, m.dispatch(ConversationEvent.ConfirmationGranted))
        assertEquals(ConversationState.Speaking, m.dispatch(ConversationEvent.ToolFinished))
    }

    @Test
    fun `empty transcript is a recoverable error`() {
        val m = machine()
        toState(m, ConversationState.Transcribing)
        val s = m.dispatch(ConversationEvent.TranscriptReady("   "))
        assertTrue(s is ConversationState.RecoverableError)
        assertEquals("empty_transcript", (s as ConversationState.RecoverableError).code)
    }

    @Test
    fun `permission denied is surfaced and recoverable`() {
        val m = machine()
        toState(m, ConversationState.PreparingAudio)
        assertEquals(ConversationState.PermissionRequired, m.dispatch(ConversationEvent.PermissionDenied))
        assertEquals(ConversationState.PreparingAudio, m.dispatch(ConversationEvent.StartRequested))
    }

    @Test
    fun `bluetooth loss during listening surfaces bluetooth unavailable`() {
        val m = machine()
        toState(m, ConversationState.Listening)
        assertEquals(ConversationState.BluetoothUnavailable, m.dispatch(ConversationEvent.BluetoothLost))
    }

    @Test
    fun `timeout in thinking is recoverable and restartable`() {
        val m = machine()
        toState(m, ConversationState.ThinkingLocal)
        val s = m.dispatch(ConversationEvent.Timeout)
        assertTrue(s is ConversationState.RecoverableError)
        assertEquals(ConversationState.PreparingAudio, m.dispatch(ConversationEvent.StartRequested))
    }

    @Test
    fun `fatal failure is terminal until reset`() {
        val m = machine()
        toState(m, ConversationState.ThinkingLocal)
        val s = m.dispatch(ConversationEvent.FatalFailure("oom"))
        assertTrue(s is ConversationState.FatalError)
        // ignored events do not move it
        assertEquals(s, m.dispatch(ConversationEvent.AnswerReady))
        assertEquals(ConversationState.Idle, m.dispatch(ConversationEvent.Reset))
    }

    @Test
    fun `phase1 skip to speaking transitions from finalizing`() {
        val m = machine()
        toState(m, ConversationState.FinalizingSpeech)
        assertEquals(ConversationState.Speaking, m.dispatch(ConversationEvent.SkipToSpeaking))
    }

    @Test
    fun `recoverable failure from any active state is recoverable`() {
        val m = machine()
        toState(m, ConversationState.Listening)
        val s = m.dispatch(ConversationEvent.RecoverableFailure("audio_focus_lost"))
        assertTrue(s is ConversationState.RecoverableError)
        assertEquals("audio_focus_lost", (s as ConversationState.RecoverableError).code)
    }

    @Test
    fun `illegal transitions are ignored`() {
        val m = machine()
        // AnswerReady while Idle is meaningless and must be a no-op
        assertEquals(ConversationState.Idle, m.dispatch(ConversationEvent.AnswerReady))
    }

    @Test
    fun `state is observable as a flow`() = runTest {
        val m = machine()
        m.state.test {
            assertEquals(ConversationState.Idle, awaitItem())
            m.dispatch(ConversationEvent.StartRequested)
            assertEquals(ConversationState.PreparingAudio, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- helpers ---------------------------------------------------------

    private fun drive(m: ConversationStateMachine, vararg events: ConversationEvent) {
        events.forEach { m.dispatch(it) }
    }

    /** Drives the machine into [target] via the canonical happy path prefix. */
    private fun toState(m: ConversationStateMachine, target: ConversationState) {
        // Common prefix up to (and including) Routing.
        m.dispatch(ConversationEvent.StartRequested)   // -> PreparingAudio
        if (target == ConversationState.PreparingAudio) return
        m.dispatch(ConversationEvent.AudioReady)        // -> Listening
        if (target == ConversationState.Listening) return
        m.dispatch(ConversationEvent.SpeechEnded)       // -> FinalizingSpeech
        if (target == ConversationState.FinalizingSpeech) return
        m.dispatch(ConversationEvent.SpeechEnded)       // -> Transcribing
        if (target == ConversationState.Transcribing) return
        m.dispatch(ConversationEvent.TranscriptReady("ciao")) // -> RetrievingMemory
        if (target == ConversationState.RetrievingMemory) return
        m.dispatch(ConversationEvent.MemoryRetrieved)   // -> Routing
        if (target == ConversationState.Routing) return

        if (target == ConversationState.ThinkingRemote) {
            m.dispatch(ConversationEvent.Routed(RouteTarget.REMOTE_PC))
            return
        }
        m.dispatch(ConversationEvent.Routed(RouteTarget.LOCAL)) // -> ThinkingLocal
        if (target == ConversationState.ThinkingLocal) return
        m.dispatch(ConversationEvent.ConfirmationRequired)      // -> AwaitingConfirmation
        if (target == ConversationState.AwaitingConfirmation) return
        m.dispatch(ConversationEvent.ConfirmationGranted)       // -> ExecutingTool
        if (target == ConversationState.ExecutingTool) return
        m.dispatch(ConversationEvent.ToolFinished)              // -> Speaking
        if (target == ConversationState.Speaking) return
        m.dispatch(ConversationEvent.SpeechSynthesisFinished)   // -> FollowUpWindow
        if (target == ConversationState.FollowUpWindow) return
        error("toState cannot reach $target via happy path")
    }
}
