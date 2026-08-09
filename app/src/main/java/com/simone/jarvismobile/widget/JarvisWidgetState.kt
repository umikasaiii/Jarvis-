package com.simone.jarvismobile.widget

import com.simone.jarvismobile.core.state.ConversationState

/** The short status a widget shows for the assistant's live state. */
object JarvisWidgetState {

    /** Maps the assistant state machine to the widget label the user reads. */
    fun label(state: ConversationState, modelReady: Boolean): String = when (state) {
        ConversationState.Idle,
        ConversationState.FollowUpWindow,
        ConversationState.Cancelled,
        -> if (modelReady) "Pronto" else "Offline"
        ConversationState.PreparingAudio,
        ConversationState.Listening,
        -> "Ascolto…"
        ConversationState.FinalizingSpeech,
        ConversationState.Transcribing,
        ConversationState.RetrievingMemory,
        ConversationState.Routing,
        ConversationState.ThinkingLocal,
        ConversationState.ThinkingRemote,
        ConversationState.ExecutingTool,
        -> "Elaborazione…"
        ConversationState.Speaking -> "Parla…"
        ConversationState.PermissionRequired -> "Permesso microfono"
        ConversationState.ModelUnavailable -> "Nessun modello"
        else -> "Offline"
    }
}
