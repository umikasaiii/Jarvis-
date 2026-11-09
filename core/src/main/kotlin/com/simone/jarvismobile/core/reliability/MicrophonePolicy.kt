package com.simone.jarvismobile.core.reliability

import com.simone.jarvismobile.core.state.ConversationState

/**
 * BETA 8 — who may use the one on-device speech recognizer.
 *
 * The assistant and the live translator share a single recognizer instance. A newer `transcribe()` supersedes the older
 * one, so if both ran, each would silently cancel the other and the translator loop would keep restarting against the
 * assistant. The rule is "the last explicit request wins, and the loser is stopped visibly":
 *  - the user starts an assistant turn (orb, wake word, widget) while a translation session runs → the translator is
 *    PAUSED (it can be resumed from its own screen), never left competing;
 *  - the user starts the translator while the assistant is capturing speech → that assistant turn is cancelled.
 * A translator started by the assistant's own voice command is not "capturing speech" at that point (the turn is
 * already past listening), so the confirmation reply is never cut off.
 */
object MicrophonePolicy {
    /** @return true when the translator must be paused before the assistant opens the microphone. */
    fun pauseTranslatorForAssistant(translatorActive: Boolean): Boolean = translatorActive

    /** @return true when the assistant turn must be cancelled because the translator just took the microphone. */
    fun cancelAssistantForTranslator(assistant: ConversationState): Boolean = when (assistant) {
        ConversationState.PreparingAudio,
        ConversationState.Listening,
        ConversationState.FinalizingSpeech,
        ConversationState.Transcribing,
        ConversationState.FollowUpWindow,
        -> true
        else -> false
    }
}
