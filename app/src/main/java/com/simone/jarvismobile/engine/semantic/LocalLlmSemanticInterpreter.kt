package com.simone.jarvismobile.engine.semantic

import android.util.Log
import com.simone.jarvismobile.core.semantic.SemanticDialogueContext
import com.simone.jarvismobile.core.semantic.SemanticInterpretation
import com.simone.jarvismobile.core.semantic.SemanticInterpreter
import com.simone.jarvismobile.core.semantic.SemanticInterpreterPrompt
import com.simone.jarvismobile.core.semantic.SemanticOutputParser
import com.simone.jarvismobile.llm.ClassifierEngineProvider
import com.simone.jarvismobile.util.runCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § FASE 2A.9 §2/§11 — the real, on-device [SemanticInterpreter]. Depends
 * only on [ClassifierEngineProvider] — the SAME seam
 * [com.simone.jarvismobile.tools.LlmIntentClassifier] already uses for an
 * analogous "classify, never answer" role — never on a hardcoded model name:
 * `LlmRouter.classifierEngine()` picks a dedicated classifier model when one
 * is imported, otherwise silently reuses the fast engine with zero extra RAM
 * (§ audit finding, `LlmRouter.kt`/`docs/MODELS.md` — this is verified
 * existing behavior, not assumed). Swapping the underlying model (a smaller
 * one, a dedicated classifier, a future remote classifier) needs no change
 * here or in any caller — only in what `classifierEngine()` itself returns.
 *
 * Uses [com.simone.jarvismobile.llm.LlmEngine.generate] — the truly
 * stateless call (fresh, immediately-discarded native `Conversation`, no
 * system prompt persisted, closed before this call returns; see
 * `LitertLmEngine.kt`) — never [com.simone.jarvismobile.llm.LlmEngine.chat]/
 * `chatStateless`'s persistent-conversation forms, so a call here can never
 * contaminate a later turn's KV cache and never inherits stale KV state from
 * a previous one. § FASE 2A.9 §18 honest limit: when no dedicated classifier
 * model is imported, this contends for the SAME per-engine `chatMutex` as
 * any concurrently in-flight conversational generation on the fast engine —
 * bounded by `LitertLmEngine.LOCK_WAIT_TIMEOUT_MS` (4s), after which this
 * call returns null (busy) rather than blocking indefinitely; measured as
 * [SemanticInterpretationResult.latencyMs] by the caller.
 *
 * Never authorizes a side effect, never answers Simone — [interpret] returns
 * ONLY a [SemanticInterpretation], parsed by the same strict,
 * closed-vocabulary [SemanticOutputParser] regardless of which engine
 * produced the raw text.
 */
@Singleton
class LocalLlmSemanticInterpreter @Inject constructor(
    private val engineProvider: ClassifierEngineProvider,
) : SemanticInterpreter {

    override suspend fun interpret(text: String, dialogueContext: SemanticDialogueContext): SemanticInterpretation {
        val hint = dialogueContext.previousFrame?.domains
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",") { it.name }
        val prompt = SemanticInterpreterPrompt.build(text, hint)
        // § runCancellable, never runCatching: generate() is a suspend call —
        // a real user-initiated cancellation must propagate, never be
        // silently read as "engine unavailable".
        val raw = runCancellable {
            engineProvider.classifierEngine().generate(prompt, timeoutSeconds = INTERPRETER_TIMEOUT_SECONDS)
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            Log.i(TAG, "semantic_interpreter_unavailable")
            return SemanticInterpretation.Invalid("engine_unavailable")
        }
        return SemanticOutputParser.parse(raw)
    }

    private companion object {
        const val TAG = "SemanticInterpreter"

        // § short, like LlmIntentClassifier's own 15s: this is a compact
        // classification call, never a full conversational generation — a
        // stuck/slow model must fail fast so the caller falls back to the
        // legacy keyword path instead of stalling the whole turn.
        const val INTERPRETER_TIMEOUT_SECONDS = 12L
    }
}
