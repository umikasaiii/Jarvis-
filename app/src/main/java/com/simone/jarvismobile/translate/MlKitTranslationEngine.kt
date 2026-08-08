package com.simone.jarvismobile.translate

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.simone.jarvismobile.core.translate.TranslationEngine
import com.simone.jarvismobile.core.translate.TranslationResult
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device translation with ML Kit. Fully offline once the language models are
 * present (see [TranslationModelManager]); a translate call never reaches the
 * network for a downloaded pair.
 *
 * Clients are cached per source→target pair and kept warm, so a back-and-forth
 * conversation doesn't rebuild a translator (and reload a model) every segment —
 * that is what keeps latency low.
 */
@Singleton
class MlKitTranslationEngine @Inject constructor() : TranslationEngine {

    override val id: String = TranslationEngine.ML_KIT_LOCAL

    override fun isOffline(sourceLanguage: String, targetLanguage: String): Boolean = true

    private val clients = ConcurrentHashMap<String, Translator>()

    private fun client(source: String, target: String): Translator? {
        val src = TranslateLanguage.fromLanguageTag(source) ?: return null
        val tgt = TranslateLanguage.fromLanguageTag(target) ?: return null
        return clients.getOrPut("$src>$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build(),
            )
        }
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TranslationResult.Failure("empty")
        if (sourceLanguage == targetLanguage) {
            return TranslationResult.Ok(trimmed, sourceLanguage, targetLanguage, 0)
        }
        val translator = client(sourceLanguage, targetLanguage)
            ?: return TranslationResult.Failure("unsupported_pair")
        val start = SystemClock.elapsedRealtime()
        return try {
            // translate() never auto-downloads: with the model present it runs
            // offline, and without it fails cleanly (the caller checks first).
            val out = translator.translate(trimmed).awaitResult()
            TranslationResult.Ok(out, sourceLanguage, targetLanguage, SystemClock.elapsedRealtime() - start)
        } catch (e: Throwable) {
            Log.w(TAG, "mlkit_translate_failed ${e.javaClass.simpleName}")
            TranslationResult.Failure(e.javaClass.simpleName)
        }
    }

    /** Closes every cached translator. Call when the session ends. */
    fun release() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private companion object {
        const val TAG = "JarvisMlKitTranslate"
    }
}

/**
 * Picks the translation engine for the selected backend. Only ML Kit is wired
 * today; the router exists so an NLLB/Seamless/PC/cloud engine can be added
 * without touching the session.
 */
@Singleton
class TranslationEngineRouter @Inject constructor(
    private val mlKit: MlKitTranslationEngine,
) {
    fun engine(backendId: String = TranslationEngine.ML_KIT_LOCAL): TranslationEngine = when (backendId) {
        else -> mlKit
    }

    fun releaseAll() = mlKit.release()
}
