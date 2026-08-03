package com.simone.jarvismobile.core.state

/**
 * Explicit, testable conversation state machine (docs/ARCHITECTURE.md §6).
 *
 * The happy path is:
 *   Idle → PreparingAudio → Listening → FinalizingSpeech → Transcribing →
 *   RetrievingMemory → Routing → ThinkingLocal|ThinkingRemote →
 *   [AwaitingConfirmation] → [ExecutingTool] → Speaking → FollowUpWindow → Idle
 *
 * Plus the terminal/interrupt states below. Every state is cancellable and the
 * machine is guaranteed to return to a usable state after any error.
 */
sealed interface ConversationState {
    data object Idle : ConversationState
    data object PreparingAudio : ConversationState
    data object Listening : ConversationState
    data object FinalizingSpeech : ConversationState
    data object Transcribing : ConversationState
    data object RetrievingMemory : ConversationState
    data object Routing : ConversationState
    data object ThinkingLocal : ConversationState
    data object ThinkingRemote : ConversationState
    data object AwaitingConfirmation : ConversationState
    data object ExecutingTool : ConversationState
    data object Speaking : ConversationState
    data object FollowUpWindow : ConversationState
    data object Cancelled : ConversationState
    data object PermissionRequired : ConversationState
    data object BluetoothUnavailable : ConversationState
    data object ModelUnavailable : ConversationState
    data object VaultUnavailable : ConversationState
    data object NetworkUnavailable : ConversationState

    /** Transient error the machine can recover from; carries a technical code only. */
    data class RecoverableError(val code: String) : ConversationState

    /** Unrecoverable error; carries a technical code only (no personal data). */
    data class FatalError(val code: String) : ConversationState
}

/** Where routing decided to send the request. Used to pick the thinking state. */
enum class RouteTarget { LOCAL, REMOTE_PC, HOME_ASSISTANT, DETERMINISTIC }
