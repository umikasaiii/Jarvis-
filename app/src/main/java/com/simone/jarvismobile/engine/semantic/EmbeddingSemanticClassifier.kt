package com.simone.jarvismobile.engine.semantic

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.semantic.SemanticFrame
import com.simone.jarvismobile.core.semantic.embedding.ClassifierThresholds
import com.simone.jarvismobile.core.semantic.embedding.EmbeddingVector
import com.simone.jarvismobile.core.semantic.embedding.HasSemanticTiming
import com.simone.jarvismobile.core.semantic.embedding.LearnedHeadClassifierEngine
import com.simone.jarvismobile.core.semantic.embedding.LearnedHeadExport
import com.simone.jarvismobile.core.semantic.embedding.PrototypeCorpusCodec
import com.simone.jarvismobile.core.semantic.embedding.PrototypeSemanticClassifierEngine
import com.simone.jarvismobile.core.semantic.embedding.SemanticClassificationResult
import com.simone.jarvismobile.core.semantic.embedding.SemanticClassifier
import com.simone.jarvismobile.core.semantic.embedding.SemanticEncoderContract
import com.simone.jarvismobile.core.semantic.embedding.SemanticInterpreterTiming
import com.simone.jarvismobile.core.semantic.embedding.TokenizerFormat
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
 *
 * § FASE 2A.11 ADDENDUM §11 — PRIMARY/FALLBACK: if a real trained
 * `semantic/head_weights.json` asset is present and parses (produced by
 * `tools/semantic_classifier/export.py` against a REAL EmbeddingGemma
 * embedding run — never shipped by this codebase today, see the training
 * pipeline's own honesty notes), [LearnedHeadClassifierEngine] becomes
 * primary automatically; its absence (the honest current state —
 * TRAINING_PENDING, no real embeddings have ever been computed in this
 * environment) falls back to [PrototypeSemanticClassifierEngine] exactly as
 * before. Dropping in a real export later needs NO code change here.
 */
@Singleton
class EmbeddingSemanticClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddingEngine: SemanticEmbeddingEngine,
    private val settings: SettingsRepository,
) : SemanticClassifier, HasSemanticTiming {

    private val loadMutex = Mutex()
    private val buildMutex = Mutex()
    @Volatile private var cachedClassifyFn: ((EmbeddingVector, SemanticFrame?) -> SemanticClassificationResult)? = null
    @Volatile private var cachedBackendName: String? = null
    @Volatile private var lastTiming: SemanticInterpreterTiming? = null

    override suspend fun classify(text: String, previousFrame: SemanticFrame?): SemanticClassificationResult {
        val classifyFn = ensureWarmEngine() ?: run {
            lastTiming = null
            return SemanticClassificationResult.unavailable("SEMANTIC_MODEL_UNAVAILABLE")
        }
        val startedAt = System.currentTimeMillis()
        val embedding = embeddingEngine.embed(text) ?: run {
            lastTiming = null
            return SemanticClassificationResult.unavailable("SEMANTIC_MODEL_UNAVAILABLE")
        }
        val classifyStart = System.currentTimeMillis()
        val result = classifyFn(embedding, previousFrame)
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

    /** Which backend answered the last built classify call — `"LEARNED_HEAD"` or `"PROTOTYPE"`. Debug/diagnostics only. */
    fun activeBackendName(): String? = cachedBackendName

    /**
     * Builds and caches the classify function the first time it's needed,
     * preferring a real trained head over the prototype/centroid classifier
     * — §15: "NON ricaricare... embedding dei prototipi a ogni messaggio".
     * Returns null if the embedding engine itself isn't loaded — the model
     * being unavailable is a genuine "not ready" state, never silently
     * retried into a broken/empty engine.
     */
    private suspend fun ensureWarmEngine(): ((EmbeddingVector, SemanticFrame?) -> SemanticClassificationResult)? {
        cachedClassifyFn?.let { return it }
        ensureModelLoadedFromSettings()
        if (embeddingEngine.loadState.value != EmbeddingLoadState.LOADED) return null
        return buildMutex.withLock {
            cachedClassifyFn?.let { return@withLock it }
            val thresholds = loadThresholds()

            buildLearnedHeadEngine(thresholds)?.let { engine ->
                Log.i(TAG, "semantic_classifier_primary=LEARNED_HEAD")
                cachedBackendName = "LEARNED_HEAD"
                return@withLock (engine::classify).also { cachedClassifyFn = it }
            }

            val engine = buildPrototypeEngine(thresholds) ?: return@withLock null
            Log.i(TAG, "semantic_classifier_primary=PROTOTYPE")
            cachedBackendName = "PROTOTYPE"
            (engine::classify).also { cachedClassifyFn = it }
        }
    }

    /**
     * § JARVIS Implementation Master Plan — PASSAGGIO 13 §K. The runtime's
     * own [SemanticEncoderContract], built from what [embeddingEngine]
     * actually has loaded — never invented. [TokenizerFormat.UNVERIFIED]
     * always, since the production [com.simone.jarvismobile.llm.SemanticTokenizer]
     * is [com.simone.jarvismobile.llm.NotReadyTokenizer] (§D) — a head
     * trained under [SemanticEncoderContract.CURRENT] (which declares
     * `SENTENCEPIECE_UNIGRAM`) can therefore never be compatible with this
     * runtime today, by construction, until a real verified tokenizer
     * replaces it.
     */
    internal fun runtimeEncoderContract(): SemanticEncoderContract =
        SemanticEncoderContract.UNVERIFIED.copy(
            modelSha256 = embeddingEngine.modelSha256,
            tokenizerSha256 = embeddingEngine.tokenizerSha256,
            embeddingDimension = embeddingEngine.embeddingDimension,
        )

    /**
     * § FASE 2A.11 ADDENDUM §11, extended by PASSAGGIO 13 §K — null whenever
     * no real trained head exists yet (missing asset, malformed JSON, wrong
     * schema version, a real embedding call failing for one of the head's
     * own labels), OR whenever a real head export is present but its own
     * [com.simone.jarvismobile.core.semantic.embedding.LearnedHeadExport.encoderContract]
     * is not [SemanticEncoderContract.isCompatibleWith] the runtime's
     * (mismatched preprocessing/tokenizer/pooling/normalization policy, a
     * different embedding dimension, or a non-[com.simone.jarvismobile.core.semantic.embedding.ArtifactQualification.PRODUCTION_ELIGIBLE]
     * artifact) — never a partially-built/guessed engine, and never a bare
     * dimension-only check that could accept a head trained under an
     * incompatible contract that merely happens to share a dimension.
     */
    private suspend fun buildLearnedHeadEngine(thresholds: ClassifierThresholds): LearnedHeadClassifierEngine? {
        val json = runCatching {
            context.assets.open(HEAD_WEIGHTS_ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        val export = LearnedHeadExport.parseOrNull(json) ?: run {
            Log.w(TAG, "learned_head_asset_invalid")
            return null
        }
        val runtimeContract = runtimeEncoderContract()
        if (!export.isCompatibleWithRuntime(runtimeContract)) {
            Log.w(TAG, "learned_head_contract_incompatible qualification=${export.artifactQualification}")
            return null
        }
        return LearnedHeadClassifierEngine(export, thresholds)
    }

    private suspend fun buildPrototypeEngine(thresholds: ClassifierThresholds): PrototypeSemanticClassifierEngine? {
        val corpusJson = runCatching {
            context.assets.open(CORPUS_ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: run {
            Log.w(TAG, "corpus_asset_missing")
            return null
        }
        val corpus = runCatching { PrototypeCorpusCodec.parse(corpusJson) }.getOrNull() ?: run {
            Log.w(TAG, "corpus_parse_failed")
            return null
        }
        val embedded = corpus.trainingPrototypes().mapNotNull { proto ->
            embeddingEngine.embed(proto.text)?.let { emb ->
                PrototypeSemanticClassifierEngine.EmbeddedPrototype(proto, emb)
            }
        }
        if (embedded.isEmpty()) {
            Log.w(TAG, "no_prototypes_embedded")
            return null
        }
        Log.i(TAG, "semantic_classifier_warm_built prototypeCount=${embedded.size}")
        return PrototypeSemanticClassifierEngine(embedded, thresholds)
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
        cachedClassifyFn = null
        cachedBackendName = null
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
        const val HEAD_WEIGHTS_ASSET_PATH = "semantic/head_weights.json"
    }
}
