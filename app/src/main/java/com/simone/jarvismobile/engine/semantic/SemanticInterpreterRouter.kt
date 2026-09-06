package com.simone.jarvismobile.engine.semantic

import com.simone.jarvismobile.core.semantic.SemanticDialogueContext
import com.simone.jarvismobile.core.semantic.SemanticInterpretation
import com.simone.jarvismobile.core.semantic.SemanticInterpreter
import com.simone.jarvismobile.core.semantic.embedding.HasSemanticTiming
import com.simone.jarvismobile.core.semantic.embedding.SemanticClassifierInterpreterAdapter
import com.simone.jarvismobile.core.semantic.embedding.SemanticInterpreterTiming
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.di.LegacyGemmaInterpreter
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § FASE 2A.11 §14 — the ONE place the debug A/B toggle
 * (`SettingsRepository.semanticUseLegacyGemmaInterpreter`) is read. The
 * EmbeddingGemma-backed [SemanticClassifierInterpreterAdapter] is the
 * default and only automatic authority; the legacy FASE 2A.9 generative
 * interpreter is reachable only when a human has explicitly flipped the
 * debug toggle — never chosen automatically, never a fallback on embedding
 * failure (a failed/unavailable embedding classifier already produces its
 * own honest `SemanticInterpretation.Invalid("SEMANTIC_MODEL_UNAVAILABLE")`,
 * which the existing FASE 2A.10 retry+`LegacyFallback` machinery already
 * handles correctly — routing to the OTHER interpreter instead would be a
 * second kind of fallback this phase does not add).
 *
 * This is the concrete type bound as the app's primary
 * [SemanticInterpreter] (`SemanticModule`) — `ConversationalJarvisEngine`
 * only ever sees the interface, never this class or either backend by name.
 */
@Singleton
class SemanticInterpreterRouter @Inject constructor(
    private val settings: SettingsRepository,
    embeddingClassifier: EmbeddingSemanticClassifier,
    @LegacyGemmaInterpreter private val legacyInterpreter: SemanticInterpreter,
) : SemanticInterpreter, HasSemanticTiming {

    private val embeddingInterpreter = SemanticClassifierInterpreterAdapter(embeddingClassifier)

    override suspend fun interpret(text: String, dialogueContext: SemanticDialogueContext): SemanticInterpretation {
        val useLegacy = settings.semanticUseLegacyGemmaInterpreter.first()
        return if (useLegacy) legacyInterpreter.interpret(text, dialogueContext) else embeddingInterpreter.interpret(text, dialogueContext)
    }

    /** Only the embedding backend tracks fine-grained timing (§15) — the legacy generative interpreter has no equivalent breakdown to report. */
    override fun lastSemanticTiming(): SemanticInterpreterTiming? = embeddingInterpreter.lastSemanticTiming()
}
