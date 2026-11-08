package com.simone.jarvismobile.core.translate

/** What the recognizer produced for one listening turn. */
sealed interface Heard {
    data class Text(val text: String) : Heard
    /** Listened, nothing intelligible was said (also what a cancelled turn returns). */
    data object Silence : Heard
    /** The on-device recognizer has no (installed) model for this language. */
    data class LanguageUnavailable(val language: TranslationLanguage) : Heard
    /** On-device recognition is not available at all (no microphone permission, no service…). */
    data class Unavailable(val reason: String) : Heard
    data class Failure(val code: String) : Heard
}

/** Offline speech recognition for ONE language at a time (the platform cannot do two at once). */
interface Recognizer {
    suspend fun listen(language: TranslationLanguage): Heard
    /** Ends a listen in progress; the pending [listen] returns promptly. */
    fun cancel()
}

enum class SpeakOutcome { SPOKEN, NO_VOICE, FAILED }

/** Text-to-speech in the target language; [NO_VOICE] means no offline voice for it is installed. */
interface Speaker {
    suspend fun speak(text: String, language: TranslationLanguage): SpeakOutcome
    fun stop()
}

data class Detection(val language: TranslationLanguage?, val confidence: Float)

interface LanguageDetector {
    suspend fun detect(text: String): Detection
}

/** The offline translation models: what is on the device, and whether one may be fetched now. */
interface ModelGate {
    /** Languages from [langs] whose model is NOT on the device. Never touches the network. */
    suspend fun missing(langs: Collection<TranslationLanguage>): List<TranslationLanguage>
    /** True when a download is allowed right now (network present and the Wi-Fi-only policy satisfied). */
    suspend fun canDownloadNow(): Boolean
    suspend fun download(language: TranslationLanguage): Boolean
}

interface TranslatorPrefs {
    suspend fun ttsEnabled(): Boolean
}

/** Something worth telling the user that does not end the session. */
sealed interface TranslatorNotice {
    data object Busy : TranslatorNotice
    data class NoVoice(val language: TranslationLanguage) : TranslatorNotice
    data object SpeakFailed : TranslatorNotice
    data class TranslateFailed(val code: String) : TranslatorNotice
    data class ModelMissing(val languages: List<TranslationLanguage>) : TranslatorNotice
    data class RecognizerLanguageMissing(val language: TranslationLanguage) : TranslatorNotice
    data object RecognizerUnavailable : TranslatorNotice
}

sealed interface TextOutcome {
    data class Ok(val text: String, val latencyMs: Long) : TextOutcome
    data object Empty : TextOutcome
    data object TooLong : TextOutcome
    data class ModelMissing(val languages: List<TranslationLanguage>) : TextOutcome
    data class Failed(val code: String) : TextOutcome
}

/**
 * ML Kit translates between two non-English languages by pivoting through English, so the English
 * model is needed as well (otherwise e.g. it→ja fails offline with only those two downloaded).
 */
object TranslationModels {
    fun required(a: TranslationLanguage, b: TranslationLanguage): List<TranslationLanguage> =
        buildList {
            add(a); add(b)
            if (a != TranslationLanguage.ENGLISH && b != TranslationLanguage.ENGLISH) add(TranslationLanguage.ENGLISH)
        }.distinct()
}
