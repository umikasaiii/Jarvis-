package com.simone.jarvismobile.core.translate

/**
 * How Live Translator listens and speaks. Pure data so the state can be tested
 * and driven from a ViewModel without any Android type leaking into `:core`.
 */
enum class TranslationMode {
    /** Mic open, language auto-detected between the two, spoken both ways. */
    AUTO,

    /** Like AUTO but tuned for a back-and-forth conversation. */
    CONVERSAZIONE,

    /** Two explicit buttons — "parlo io" / "interlocutore" — no auto-detect. */
    PUSH_TO_TALK,

    /** No TTS: translate and show text only. */
    SOLO_TESTO,

    /** Show original + translation and speak the translation. */
    VOCE_TESTO,

    /** Listen to the other side, translate to the main language, don't speak it. */
    SOLO_ASCOLTO;

    val speaks: Boolean get() = this == AUTO || this == CONVERSAZIONE || this == VOCE_TESTO
    val autoDetects: Boolean get() = this == AUTO || this == CONVERSAZIONE || this == SOLO_ASCOLTO
}

/**
 * The lifecycle of a live-translation session. Deliberately parallel to JARVIS's
 * own conversation states but separate, so translating never blocks or rewrites
 * the assistant's own state machine.
 */
sealed interface LiveTranslationState {
    data object Idle : LiveTranslationState
    data object CheckingModels : LiveTranslationState
    data class DownloadingModels(val language: TranslationLanguage?) : LiveTranslationState
    data object Ready : LiveTranslationState
    data object Listening : LiveTranslationState
    data object Transcribing : LiveTranslationState
    data object Translating : LiveTranslationState
    data object Speaking : LiveTranslationState
    data object Paused : LiveTranslationState
    data object Stopping : LiveTranslationState
    data class Error(val reason: String) : LiveTranslationState

    val isActive: Boolean
        get() = this is Listening || this is Transcribing || this is Translating ||
            this is Speaking || this is Ready || this is Paused
}

/** The audio sink the translation is played to. */
enum class TranslationAudioOutput { AUTO, PHONE_SPEAKER, WIRED, BLUETOOTH }

/** One configured session. Only preferences are persisted, never the transcript. */
data class TranslationSession(
    val sessionId: String,
    val languageA: TranslationLanguage,
    val languageB: TranslationLanguage,
    val mode: TranslationMode = TranslationMode.AUTO,
    val startedAt: Long = 0L,
    val audioOutput: TranslationAudioOutput = TranslationAudioOutput.AUTO,
    val ttsEnabled: Boolean = true,
    val autoDetectEnabled: Boolean = true,
) {
    fun swapped(): TranslationSession = copy(languageA = languageB, languageB = languageA)
}

/** One line of the conversation: what was heard and what it became. */
data class ConversationSegment(
    val id: Long,
    val timestamp: Long,
    val detectedLanguage: TranslationLanguage,
    val sourceText: String,
    val translatedText: String,
    val targetLanguage: TranslationLanguage,
    val confidence: Float = 1f,
    val translationLatencyMs: Long = 0L,
)
