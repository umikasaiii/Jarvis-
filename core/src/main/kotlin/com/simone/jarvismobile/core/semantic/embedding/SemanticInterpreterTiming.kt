package com.simone.jarvismobile.core.semantic.embedding

/**
 * § FASE 2A.11 §15 — the fine-grained timing breakdown the spec asks for
 * (`semanticTokenizationMs`/`semanticEmbeddingMs`/`semanticClassificationMs`/
 * `semanticTotalMs`/`modelColdStartMs`). Never available from the plain
 * [com.simone.jarvismobile.core.semantic.SemanticInterpreter] contract itself
 * (that interface only returns a
 * [com.simone.jarvismobile.core.semantic.SemanticInterpretation] — adding a
 * timing return value to every implementation, including the legacy
 * generative one that cannot break its own latency into these stages at all,
 * would be exactly the kind of interface bloat this project avoids) — an
 * implementation that CAN report this instead implements
 * [HasSemanticTiming] as a second, optional interface, read via `as?` by
 * whichever caller wants it (`ConversationalJarvisEngine`), same "set by the
 * last call" convention already used by `JarvisBrain.lastPromptDiagnostics`.
 */
data class SemanticInterpreterTiming(
    val tokenizationMs: Long?,
    val embeddingMs: Long?,
    val classificationMs: Long?,
    val totalMs: Long?,
    /** Non-null only on the very first call after a (re)load — see [EngineTurnDiagnostics.modelColdStartMs][com.simone.jarvismobile.core.engine.EngineTurnDiagnostics.modelColdStartMs]. */
    val coldStartMs: Long?,
)

/** Optional capability a [com.simone.jarvismobile.core.semantic.SemanticInterpreter] MAY implement to expose [SemanticInterpreterTiming] for its last call. */
interface HasSemanticTiming {
    fun lastSemanticTiming(): SemanticInterpreterTiming?
}
