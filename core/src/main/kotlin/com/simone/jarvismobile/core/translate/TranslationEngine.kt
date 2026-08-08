package com.simone.jarvismobile.core.translate

/** The outcome of translating one segment. */
sealed interface TranslationResult {
    data class Ok(
        val text: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val latencyMs: Long,
    ) : TranslationResult

    /** A recoverable failure (model missing, engine error). Never crashes the session. */
    data class Failure(val code: String) : TranslationResult
}

/**
 * Abstracts the translator so Live Translator never depends on one backend.
 *
 * The first implementation is ML Kit on-device ([ML_KIT_LOCAL]); an NLLB,
 * Seamless, PC-companion or cloud engine can be added later behind this same
 * interface without touching the session, the segmenter or the UI.
 */
interface TranslationEngine {
    val id: String

    /** True when this engine never needs the network for the given pair. */
    fun isOffline(sourceLanguage: String, targetLanguage: String): Boolean

    /** Translates one segment. Must not throw — errors come back as [TranslationResult.Failure]. */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult

    companion object {
        const val ML_KIT_LOCAL = "ml_kit_local"
        const val NLLB = "nllb"
        const val SEAMLESS = "seamless"
        const val PC = "pc"
        const val CLOUD = "cloud"
    }
}
