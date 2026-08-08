package com.simone.jarvismobile.core.translate

/**
 * Stabilises the language identifier so the detected language doesn't flicker
 * between near neighbours (it/es/fr look alike to a short-text detector).
 *
 * A low-confidence guess never changes anything. A confident guess for a
 * *different* language must repeat [switchThreshold] times in a row before the
 * switch is accepted; the very first detection is taken immediately so the first
 * turn isn't lost. Exposes the fields the spec asks for so the UI can show them.
 */
class LanguageStabilizer(
    private val minConfidence: Float = 0.5f,
    private val switchThreshold: Int = 2,
) {
    var current: TranslationLanguage? = null
        private set
    var previous: TranslationLanguage? = null
        private set
    var confidence: Float = 0f
        private set
    var stableCounter: Int = 0
        private set

    private var candidate: TranslationLanguage? = null

    /** Feeds one raw detection; returns the stabilised language (may be unchanged). */
    fun offer(detected: TranslationLanguage?, conf: Float): TranslationLanguage? {
        if (detected == null || conf < minConfidence) return current // hold, don't oscillate

        confidence = conf
        if (detected == current) {
            candidate = null
            stableCounter = 0
            return current
        }

        if (detected == candidate) {
            stableCounter++
        } else {
            candidate = detected
            stableCounter = 1
        }

        if (current == null || stableCounter >= switchThreshold) {
            previous = current
            current = detected
            candidate = null
            stableCounter = 0
        }
        return current
    }

    fun reset() {
        current = null
        previous = null
        confidence = 0f
        stableCounter = 0
        candidate = null
    }
}

/**
 * Bidirectional routing for an A⇄B pair: the detected language is the source, the
 * other side of the pair is the target. A detection outside the pair returns null
 * so the caller can ignore it rather than translate into the wrong language.
 */
object LivePairRouter {
    fun route(
        detected: TranslationLanguage,
        a: TranslationLanguage,
        b: TranslationLanguage,
    ): Pair<TranslationLanguage, TranslationLanguage>? = when (detected) {
        a -> a to b
        b -> b to a
        else -> null
    }
}
