package com.simone.jarvismobile.core.driving

import com.simone.jarvismobile.core.state.ConversationState

/**
 * Which large panel is open in the driving overlay. Only one at a time — opening
 * one always collapses the other, so the map underneath is never covered by two
 * things at once (spec §13).
 */
enum class DrivingExpandedPanel { NONE, MESSAGES, MEDIA }

/**
 * One conversation thread surfaced in the compact notification line. Content
 * comes from a real `StatusBarNotification`; nothing here is invented.
 */
data class DrivingNotification(
    val id: String,
    val app: String,
    val sender: String,
    val preview: String,
    val count: Int,
    val postedAtEpochMs: Long,
    val supportsReply: Boolean,
)

/**
 * The active media session, read from Android's own `MediaController` — never a
 * second, JARVIS-owned player. `hasArt`/`positionMs`/`durationMs` are nullable
 * because a session is not required to publish them.
 */
data class DrivingMediaState(
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    val positionMs: Long?,
    val durationMs: Long?,
    val hasArt: Boolean,
)

/**
 * What the compact nav panel can honestly show. Google Maps does not hand
 * turn-by-turn guidance data back to the app that launched it, so
 * [distanceToNextTurn]/[nextInstruction]/[etaMinutes] stay null on the current
 * Maps-Intent source — this is an extension point for a future connected
 * source (spec §5), never a place to put invented numbers.
 */
data class DrivingNavigationState(
    val destinationLabel: String?,
    val routePrepared: Boolean,
    val routeStarted: Boolean,
    val distanceToNextTurn: String? = null,
    val nextInstruction: String? = null,
    val etaMinutes: Int? = null,
)

/** The four states the top bar shows (spec §6), one-to-one with the JARVIS pipeline. */
enum class DrivingVoiceState { READY, LISTENING, PROCESSING, REPLYING }

data class DrivingModeState(
    val active: Boolean = false,
    val voiceState: DrivingVoiceState = DrivingVoiceState.READY,
    val expandedPanel: DrivingExpandedPanel = DrivingExpandedPanel.NONE,
    val notifications: List<DrivingNotification> = emptyList(),
    val media: DrivingMediaState? = null,
    val navigation: DrivingNavigationState? = null,
) {
    /** Expanding a panel always closes any other — see [DrivingExpandedPanel]. */
    fun expand(panel: DrivingExpandedPanel): DrivingModeState = copy(expandedPanel = panel)

    fun collapse(): DrivingModeState = copy(expandedPanel = DrivingExpandedPanel.NONE)

    /** "mostra X" toggles: expand if closed/other, collapse if already open. */
    fun toggle(panel: DrivingExpandedPanel): DrivingModeState =
        if (expandedPanel == panel) collapse() else expand(panel)
}

/**
 * Maps the app's one real voice pipeline state onto the four labels the driving
 * overlay shows (spec §6). No second state machine: this is a pure, exhaustive
 * projection of [ConversationState], so a new pipeline state cannot be silently
 * missed here — the compiler enforces it.
 */
fun ConversationState.toDrivingVoiceState(): DrivingVoiceState = when (this) {
    ConversationState.Idle,
    ConversationState.FollowUpWindow,
    ConversationState.Cancelled,
    ConversationState.PermissionRequired,
    ConversationState.BluetoothUnavailable,
    ConversationState.ModelUnavailable,
    ConversationState.VaultUnavailable,
    ConversationState.NetworkUnavailable,
    is ConversationState.RecoverableError,
    is ConversationState.FatalError,
    -> DrivingVoiceState.READY

    ConversationState.PreparingAudio,
    ConversationState.Listening,
    -> DrivingVoiceState.LISTENING

    ConversationState.FinalizingSpeech,
    ConversationState.Transcribing,
    ConversationState.RetrievingMemory,
    ConversationState.Routing,
    ConversationState.ThinkingLocal,
    ConversationState.ThinkingRemote,
    ConversationState.AwaitingConfirmation,
    ConversationState.ExecutingTool,
    -> DrivingVoiceState.PROCESSING

    ConversationState.Speaking -> DrivingVoiceState.REPLYING
}
