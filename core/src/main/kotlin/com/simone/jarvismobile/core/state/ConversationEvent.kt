package com.simone.jarvismobile.core.state

/**
 * Inputs that drive the [ConversationStateMachine]. Events are produced by the
 * UI, the audio pipeline, engines and timers. The machine itself is pure: it
 * only maps (state, event) → next state, so it is fully unit-testable.
 */
sealed interface ConversationEvent {
    /** User explicitly started a session (button / tile / notification / deep link). */
    data object StartRequested : ConversationEvent

    data object AudioReady : ConversationEvent
    data object SpeechStarted : ConversationEvent

    /** VAD detected end-of-utterance. */
    data object SpeechEnded : ConversationEvent

    data class TranscriptReady(val text: String) : ConversationEvent
    data object MemoryRetrieved : ConversationEvent

    data class Routed(val target: RouteTarget) : ConversationEvent

    /** Model/tool planner produced a reply that needs a sensitive tool confirmation. */
    data object ConfirmationRequired : ConversationEvent
    data object ConfirmationGranted : ConversationEvent
    data object ConfirmationDenied : ConversationEvent

    data object ToolFinished : ConversationEvent

    /** Model produced a spoken answer with no (further) tool to run. */
    data object AnswerReady : ConversationEvent
    data object SpeechSynthesisFinished : ConversationEvent
    data object FollowUpTimeout : ConversationEvent

    /** A brand-new command arrived mid-flight and must interrupt the current one. */
    data object BargeIn : ConversationEvent

    data object CancelRequested : ConversationEvent
    data object Timeout : ConversationEvent

    // Failure signals.
    data object PermissionDenied : ConversationEvent
    data object BluetoothLost : ConversationEvent
    data object ModelMissing : ConversationEvent
    data object VaultMissing : ConversationEvent
    data object NetworkMissing : ConversationEvent
    data class RecoverableFailure(val code: String) : ConversationEvent
    data class FatalFailure(val code: String) : ConversationEvent

    /** Return to Idle from any resting/terminal state (user dismissed / handled). */
    data object Reset : ConversationEvent
}
