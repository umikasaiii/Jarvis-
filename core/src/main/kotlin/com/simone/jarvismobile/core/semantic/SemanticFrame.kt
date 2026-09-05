package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.tools.ToolFamily

/**
 * § FASE 2A.9 — the structured result of understanding ONE user turn's
 * MEANING, before any routing decision. Replaces "does the text contain a
 * family keyword?" as the primary signal — see [SemanticInterpreter]'s own
 * doc comment for the architectural problem this closes.
 */
enum class SemanticIntent {
    DIRECT_COMMAND,
    CAPABILITY_QUERY,
    KNOWLEDGE_QUERY,
    CONVERSATION,
    MULTI_SOURCE_REASONING,
    CLARIFICATION,
    UNKNOWN,
}

/**
 * Reuses [ToolFamily] directly as the domain vocabulary (§ FASE 2A.9 spec
 * §3: "prefer reusing existing ToolFamily/capability when semantically
 * correct") — WEATHER/AGENDA/HEALTH/DEVICE_INFO/DEVICE/SYSTEM_APP/MEMORY/
 * ARCHIVE/COMMUNICATION/MEDIA/DRIVING/UTILITY/KNOWLEDGE already are exactly
 * this project's domain list; introducing a second, parallel enum would be
 * the "duplicate ConversationManager/ToolRegistry" mistake the spec
 * explicitly forbids.
 */
typealias SemanticDomain = ToolFamily

enum class SemanticOperation {
    GET, LIST, SUMMARIZE, COMPARE, CONTROL, SEARCH, RECOMMEND,
    OPEN, CREATE, UPDATE, DELETE, UNKNOWN,
}

/** How strongly the current turn refers back to the previous one. */
enum class ReferenceMode {
    /** The turn is fully self-sufficient — no continuation implied. */
    NONE,

    /** A bare pronoun/partitive ("quanta ne ho?") with no noun of its own. */
    PARTITIVE,

    /** An elliptical continuation ("e dopodomani?", "e la media?") — same topic, one changed slot. */
    ELLIPSIS,
}

/** Which [SemanticFrame] fields the interpreter filled from real signal in THIS turn's text — never from a default. */
enum class SemanticSlot { DOMAINS, OPERATION, TEMPORAL_EXPRESSION, METRIC, AGGREGATION, ENTITIES }

/**
 * A small, general, typed model of one turn's meaning (§ FASE 2A.9 §3).
 * Deliberately does NOT itself resolve dates/periods/metrics into concrete
 * values — [temporalExpression]/[metric]/[aggregation] are preserved as the
 * ORIGINAL words the interpreter recognized (or null); the specialized
 * parsers this project already has (`ItalianDateTimeParser`,
 * `HealthQueryParser`, `AgendaWeekRange`, weather day parsing) do the actual
 * normalization downstream — see those classes.
 *
 * [explicitSlots] is the merge algorithm's ground truth (§6): a slot absent
 * from this set was never asserted by the current turn's own words, so
 * [SemanticFrameMerger] is the ONLY place allowed to fill it in from a
 * previous frame — a slot present here can never be overwritten by
 * inheritance, which is exactly the "current turn beats previous context"
 * invariant (§ permanent architectural rule).
 */
data class SemanticFrame(
    val intent: SemanticIntent,
    val domains: Set<SemanticDomain>,
    val operation: SemanticOperation,
    val temporalExpression: String?,
    val metric: String?,
    val aggregation: String?,
    val entities: List<String>,
    val referenceMode: ReferenceMode,
    val requiresGrounding: Boolean,
    val confidence: Double,
    val explicitSlots: Set<SemanticSlot>,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    companion object {
        const val SCHEMA_VERSION = 1

        fun unknown(): SemanticFrame = SemanticFrame(
            intent = SemanticIntent.UNKNOWN,
            domains = emptySet(),
            operation = SemanticOperation.UNKNOWN,
            temporalExpression = null,
            metric = null,
            aggregation = null,
            entities = emptyList(),
            referenceMode = ReferenceMode.NONE,
            requiresGrounding = false,
            confidence = 0.0,
            explicitSlots = emptySet(),
        )
    }
}

/** Read-only operations the Semantic Router may resolve directly against a capability builder — never a side effect (§ FASE 2A.9 §12: "the Semantic Interpreter NEVER authorizes a side effect"). */
val READ_ONLY_OPERATIONS: Set<SemanticOperation> = setOf(
    SemanticOperation.GET, SemanticOperation.LIST, SemanticOperation.SUMMARIZE,
    SemanticOperation.COMPARE, SemanticOperation.SEARCH, SemanticOperation.RECOMMEND,
)
