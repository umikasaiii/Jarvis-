package com.simone.jarvismobile.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure conversation state machine. Holds no Android, audio, model or IO
 * dependencies — it is a deterministic reducer over [ConversationEvent]s whose
 * current [state] is observable as a [StateFlow] for the UI.
 *
 * Design rules enforced here (docs/ARCHITECTURE.md §6):
 *  - every non-terminal state accepts [ConversationEvent.CancelRequested] → Cancelled;
 *  - every state accepts a fatal/recoverable failure and lands somewhere usable;
 *  - a [ConversationEvent.BargeIn] during Speaking/FollowUp restarts capture;
 *  - unknown (state, event) pairs are ignored (no illegal transitions, no crash).
 */
class ConversationStateMachine(
    initial: ConversationState = ConversationState.Idle,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    val current: ConversationState get() = _state.value

    /**
     * Applies [event] to the current state and returns the resulting state.
     * Returns the unchanged state for transitions that are not allowed.
     */
    fun dispatch(event: ConversationEvent): ConversationState {
        val next = reduce(_state.value, event)
        _state.value = next
        return next
    }

    private fun reduce(state: ConversationState, event: ConversationEvent): ConversationState {
        // Universal handlers first — they apply from (almost) any state.
        when (event) {
            is ConversationEvent.CancelRequested ->
                return if (state.isTerminal()) state else ConversationState.Cancelled
            is ConversationEvent.RecoverableFailure ->
                return if (state.isTerminal()) state else ConversationState.RecoverableError(event.code)
            is ConversationEvent.FatalFailure -> return ConversationState.FatalError(event.code)
            is ConversationEvent.PermissionDenied -> return ConversationState.PermissionRequired
            is ConversationEvent.ModelMissing -> return ConversationState.ModelUnavailable
            is ConversationEvent.VaultMissing -> return ConversationState.VaultUnavailable
            is ConversationEvent.Reset -> return ConversationState.Idle
            else -> Unit
        }

        return when (state) {
            ConversationState.Idle -> when (event) {
                ConversationEvent.StartRequested -> ConversationState.PreparingAudio
                else -> state
            }

            ConversationState.PreparingAudio -> when (event) {
                ConversationEvent.AudioReady -> ConversationState.Listening
                ConversationEvent.BluetoothLost -> ConversationState.BluetoothUnavailable
                ConversationEvent.Timeout -> ConversationState.RecoverableError("audio_prepare_timeout")
                else -> state
            }

            ConversationState.Listening -> when (event) {
                ConversationEvent.SpeechStarted -> ConversationState.Listening
                ConversationEvent.SpeechEnded -> ConversationState.FinalizingSpeech
                ConversationEvent.BluetoothLost -> ConversationState.BluetoothUnavailable
                ConversationEvent.Timeout -> ConversationState.RecoverableError("listen_timeout")
                else -> state
            }

            ConversationState.FinalizingSpeech -> when (event) {
                ConversationEvent.SpeechEnded, ConversationEvent.AudioReady -> ConversationState.Transcribing
                // Phase-1 shortcut: no STT/LLM yet, speak a fixed reply.
                ConversationEvent.SkipToSpeaking -> ConversationState.Speaking
                ConversationEvent.Timeout -> ConversationState.RecoverableError("finalize_timeout")
                else -> state
            }

            ConversationState.Transcribing -> when (event) {
                is ConversationEvent.TranscriptReady ->
                    if (event.text.isBlank()) ConversationState.RecoverableError("empty_transcript")
                    else ConversationState.RetrievingMemory
                ConversationEvent.Timeout -> ConversationState.RecoverableError("stt_timeout")
                else -> state
            }

            ConversationState.RetrievingMemory -> when (event) {
                ConversationEvent.MemoryRetrieved -> ConversationState.Routing
                ConversationEvent.VaultMissing -> ConversationState.VaultUnavailable
                ConversationEvent.Timeout -> ConversationState.Routing // retrieval is best-effort
                else -> state
            }

            ConversationState.Routing -> when (event) {
                is ConversationEvent.Routed -> when (event.target) {
                    RouteTarget.REMOTE_PC -> ConversationState.ThinkingRemote
                    RouteTarget.LOCAL, RouteTarget.DETERMINISTIC, RouteTarget.HOME_ASSISTANT ->
                        ConversationState.ThinkingLocal
                }
                ConversationEvent.NetworkMissing -> ConversationState.ThinkingLocal // fall back to local
                else -> state
            }

            ConversationState.ThinkingLocal, ConversationState.ThinkingRemote -> when (event) {
                ConversationEvent.ConfirmationRequired -> ConversationState.AwaitingConfirmation
                ConversationEvent.AnswerReady -> ConversationState.Speaking
                ConversationEvent.NetworkMissing ->
                    if (state == ConversationState.ThinkingRemote) ConversationState.ThinkingLocal
                    else ConversationState.NetworkUnavailable
                ConversationEvent.Timeout -> ConversationState.RecoverableError("think_timeout")
                else -> state
            }

            ConversationState.AwaitingConfirmation -> when (event) {
                ConversationEvent.ConfirmationGranted -> ConversationState.ExecutingTool
                ConversationEvent.ConfirmationDenied -> ConversationState.Speaking
                ConversationEvent.Timeout -> ConversationState.Speaking // treat as declined
                else -> state
            }

            ConversationState.ExecutingTool -> when (event) {
                ConversationEvent.ToolFinished -> ConversationState.Speaking
                ConversationEvent.Timeout -> ConversationState.RecoverableError("tool_timeout")
                else -> state
            }

            ConversationState.Speaking -> when (event) {
                ConversationEvent.SpeechSynthesisFinished -> ConversationState.FollowUpWindow
                ConversationEvent.BargeIn -> ConversationState.PreparingAudio
                else -> state
            }

            ConversationState.FollowUpWindow -> when (event) {
                ConversationEvent.SpeechStarted, ConversationEvent.BargeIn -> ConversationState.PreparingAudio
                ConversationEvent.FollowUpTimeout -> ConversationState.Idle
                else -> state
            }

            // Resting / terminal states: recover to Idle on explicit start or reset.
            ConversationState.Cancelled,
            ConversationState.PermissionRequired,
            ConversationState.BluetoothUnavailable,
            ConversationState.ModelUnavailable,
            ConversationState.VaultUnavailable,
            ConversationState.NetworkUnavailable,
            is ConversationState.RecoverableError -> when (event) {
                ConversationEvent.StartRequested -> ConversationState.PreparingAudio
                else -> state
            }

            is ConversationState.FatalError -> when (event) {
                ConversationEvent.StartRequested -> ConversationState.PreparingAudio
                else -> state
            }
        }
    }
}

/** Terminal/resting states from which the machine only leaves via Reset/StartRequested. */
fun ConversationState.isTerminal(): Boolean = when (this) {
    ConversationState.Idle,
    ConversationState.Cancelled,
    ConversationState.PermissionRequired,
    ConversationState.BluetoothUnavailable,
    ConversationState.ModelUnavailable,
    ConversationState.VaultUnavailable,
    ConversationState.NetworkUnavailable,
    is ConversationState.RecoverableError,
    is ConversationState.FatalError -> true
    else -> false
}
