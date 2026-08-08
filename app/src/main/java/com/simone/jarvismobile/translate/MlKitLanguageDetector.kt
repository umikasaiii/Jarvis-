package com.simone.jarvismobile.translate

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.simone.jarvismobile.core.translate.TranslationLanguage
import javax.inject.Inject
import javax.inject.Singleton

/** A language guess restricted to the supported set, with its confidence. */
data class DetectedLanguage(val language: TranslationLanguage?, val confidence: Float)

/**
 * On-device language identification (ML Kit language-id). Runs on the *text* the
 * recognizer produced, never on audio, and never on the network. The result is
 * mapped into [TranslationLanguage]: a language outside the supported five (or an
 * undetermined guess) comes back as `null`, which the caller treats as "keep the
 * current language" rather than switching to something it can't translate.
 */
@Singleton
class MlKitLanguageDetector @Inject constructor() {

    // A low threshold here just lets a candidate through; the real anti-flicker
    // guard is the LanguageStabilizer in :core, which needs repeated agreement
    // before it actually switches sides.
    private val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.30f)
            .build(),
    )

    /**
     * Identifies the dominant language of [text]. Returns a supported language
     * with its confidence, or `null` language when ML Kit is unsure or the text
     * is in a language we don't handle.
     */
    suspend fun detect(text: String): DetectedLanguage {
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS) return DetectedLanguage(null, 0f)
        return runCatching {
            val identified = client.identifyPossibleLanguages(trimmed).awaitResult()
            // identifyPossibleLanguages returns candidates sorted by confidence;
            // pick the best one that is actually in our supported set so a stray
            // high-confidence "und"/unsupported guess can't hijack the pair.
            val best = identified
                .sortedByDescending { it.confidence }
                .firstNotNullOfOrNull { cand ->
                    TranslationLanguage.fromCode(cand.languageTag)?.let { it to cand.confidence }
                }
            if (best == null) DetectedLanguage(null, 0f)
            else DetectedLanguage(best.first, best.second)
        }.getOrElse {
            Log.w(TAG, "language_id_failed ${it.javaClass.simpleName}")
            DetectedLanguage(null, 0f)
        }
    }

    private companion object {
        const val TAG = "JarvisLangId"
        // Very short strings ("sì", "ok") are ambiguous across languages; don't
        // let them flip the pair.
        const val MIN_CHARS = 3
    }
}
