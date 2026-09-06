package com.simone.jarvismobile.engine.semantic

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.semantic.SemanticFrame
import com.simone.jarvismobile.core.semantic.embedding.ClassifierThresholds
import com.simone.jarvismobile.core.semantic.embedding.HasSemanticTiming
import com.simone.jarvismobile.core.semantic.embedding.PrototypeCorpusCodec
import com.simone.jarvismobile.core.semantic.embedding.PrototypeSemanticClassifierEngine
import com.simone.jarvismobile.core.semantic.embedding.SemanticClassificationResult
import com.simone.jarvismobile.core.semantic.embedding.SemanticClassifier
import com.simone.jarvismobile.core.semantic.embedding.SemanticInterpreterTiming
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.EmbeddingLoadState
import com.simone.jarvismobile.llm.SemanticEmbeddingEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § FASE 2A.11 — the real on-device [SemanticClassifier]: embeds [text] via
 * [embeddingEngine], classifies it against the corpus's prototype centroids
 * (pure `:core` [PrototypeSemanticClassifierEngine], warm-built once and
 * cached — never re-embedding all ~176 prototypes on every turn). Bound as
 * the app's primary [com.simone.jarvismobile.core.semantic.SemanticInterpreter]
 * via [SemanticClassifierInterpreterAdapter][com.simone.jarvismobile.core.semantic.embedding.SemanticClassifierInterpreterAdapter]
 * in `SemanticModule` — `ConversationalJarvisEngine`'s entire retry/
 * validate/merge/route pipeline is reused unchanged (§16).
 *
 * A failed embed (model not loaded/inference error) becomes
 * [SemanticClassificationResult.unavailable] with reason
 * `SEMANTIC_MODEL_UNAVAILABLE` (§14) — the adapter turns that into
 * [com.simone.jarvismobile.core.semantic.SemanticInterpretation.Invalid],
 * which (since FASE 2A.10) goes straight to the full reasoning loop, never a
 * keyword fallback.
 */
@Singleton
class EmbeddingSemanticClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddingEngine: SemanticEmbeddingEngine,
    private val settings: SettingsRepository,
) : SemanticClassifier, HasSemanticTiming {

    private val loadMutex = Mutex()
    private val buildMutex = Mutex()
    @Volatile private var cachedEngine: PrototypeSemanticClassifierEngine? = null
    @Volatile private var lastTiming: SemanticInterpreterTiming? = null

    override suspend fun classify(text: String, previousFrame: SemanticFrame?): SemanticClassificationResult {
        val engine = ensureWarmEngine() ?: run {
            lastTiming = null
            return SemanticClassificationResult.unavailable("SEMANTIC_MODEL_UNAVAILABLE")
        }
        val startedAt = System.currentTimeMillis()
        val embedding = embeddingEngine.embed(text) ?: run {
            lastTiming = null
            return SemanticClassificationResult.unavailable("SEMANTIC_MODEL_UNAVAILABLE")
        }
        val classifyStart = System.currentTimeMillis()
        val result = engine.classify(embedding, previousFrame)
        val classifyMs = System.currentTimeMillis() - classifyStart
        val engineTiming = embeddingEngine.lastTiming
        lastTiming = SemanticInterpreterTiming(
            tokenizationMs = engineTiming?.tokenizationMs,
            embeddingMs = engineTiming?.embeddingMs,
            classificationMs = classifyMs,
            totalMs = System.currentTimeMillis() - startedAt,
            coldStartMs = engineTiming?.coldStartMs,
        )
        return result
    }

    override fun lastSemanticTiming(): SemanticInterpreterTiming? = lastTiming

    /**
     * Builds the [PrototypeSemanticClassifierEngine] the first time it's
     * needed (embedding every TRAINING-split prototype once), then reuses it
     * — §15: "NON ricaricare... embedding dei prototipi a ogni messaggio".
     * Returns null if the embedding engine itself isn't loaded — the model
     * being unavailable is a genuine "not ready" state, never silently
     * retried into a broken/empty engine.
     */
    private suspend fun ensureWarmEngine(): PrototypeSemanticClassifierEngine? {
        cachedEngine?.let { return it }
        ensureModelLoadedFromSettings()
        if (embeddingEngine.loadState.value != EmbeddingLoadState.LOADED) return null
        return buildMutex.withLock {
            cachedEngine?.let { return@withLock it }
            val corpusJson = runCatching {
                context.assets.open(CORPUS_ASSET_PATH).bufferedReader().use { it.readText() }
            }.getOrNull() ?: run {
                Log.w(TAG, "corpus_asset_missing")
                return@withLock null
            }
            val corpus = runCatching { PrototypeCorpusCodec.parse(corpusJson) }.getOrNull() ?: run {
                Log.w(TAG, "corpus_parse_failed")
                return@withLock null
            }
            val thresholds = loadThresholds()
            val embedded = corpus.trainingPrototypes().mapNotNull { proto ->
                embeddingEngine.embed(proto.text)?.let { emb ->
                    PrototypeSemanticClassifierEngine.EmbeddedPrototype(proto, emb)
                }
            }
            if (embedded.isEmpty()) {
                Log.w(TAG, "no_prototypes_embedded")
                return@withLock null
            }
            Log.i(TAG, "semantic_classifier_warm_built prototypeCount=${embedded.size}")
            PrototypeSemanticClassifierEngine(embedded, thresholds).also { cachedEngine = it }
        }
    }

    /**
     * § FASE 2A.11 §6 — real calibrated thresholds, when present, override
     * [ClassifierThresholds.CONSERVATIVE_DEFAULT]: the SAME JSON shape
     * `tools/semantic_classifier/calibrate.py` produces, so adopting a real
     * calibration run needs only dropping a new
     * `semantic/thresholds.json` asset — no code change. Its absence
     * (the expected case until a real EmbeddingGemma calibration run has
     * actually happened) is not an error — falls back silently to the
     * conservative default.
     */
    private fun loadThresholds(): ClassifierThresholds =
        runCatching {
            context.assets.open(THRESHOLDS_ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull()?.let { json ->
            runCatching { ClassifierThresholds.parse(json) }.getOrNull()
        } ?: ClassifierThresholds.CONSERVATIVE_DEFAULT

    /** Forces a rebuild on the next [classify] call — used when the embedding model is (re)loaded, so a stale cache never survives a model swap. */
    fun invalidate() {
        cachedEngine = null
    }

    /**
     * § FASE 2A.11 §14/§15 — lazy load on first real use, same pattern as
     * `memory/EmbeddingRepository.ensureLoaded()` (the OTHER on-device
     * embedding model already in this project, for Memory V2 retrieval):
     * no-op if already loaded/loading, if no model was imported, or if the
     * stored file is gone — the app then behaves exactly like a fresh
     * install with nothing imported (`SEMANTIC_MODEL_UNAVAILABLE`, §14),
     * never a crash and never a silent keyword fallback.
     */
    private suspend fun ensureModelLoadedFromSettings() {
        if (embeddingEngine.loadState.value == EmbeddingLoadState.LOADED) return
        loadMutex.withLock {
            if (embeddingEngine.loadState.value == EmbeddingLoadState.LOADED) return@withLock
            val modelPath = settings.semanticClassifierTflitePath.first()
            val tokenizerPath = settings.semanticClassifierTokenizerPath.first()
            if (modelPath.isBlank() || tokenizerPath.isBlank()) return@withLock
            if (!File(modelPath).exists() || !File(tokenizerPath).exists()) return@withLock
            val modelName = settings.semanticClassifierTfliteName.first().ifBlank { File(modelPath).name }
            embeddingEngine.load(modelPath, tokenizerPath, modelName)
        }
    }

    private companion object {
        const val TAG = "EmbeddingSemanticClassifier"
        const val CORPUS_ASSET_PATH = "semantic/prototypes.json"
        const val THRESHOLDS_ASSET_PATH = "semantic/thresholds.json"
    }
}
