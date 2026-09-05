package com.simone.jarvismobile.core.engine

/**
 * § FASE 2A.5 diagnostica richiesta esplicitamente — one turn's worth of
 * prompt-construction telemetry, privacy-safe by construction: every field
 * is a count, an enum name, or a tier label, never the prompt text, the
 * tool arguments, or any user content. Populated by `JarvisBrain` each time
 * it builds a turn's system prompt (`systemPromptFor`) and read back by
 * `ConversationalJarvisEngine` into [EngineTurnDiagnostics] — the SAME
 * existing diagnostics surface, not a second one.
 */
data class PromptDiagnostics(
    val tier: SystemPromptComposer.Tier,
    val availableToolCount: Int,
    val selectedToolCount: Int,
    /** [com.simone.jarvismobile.core.tools.ToolFamily] names, e.g. `["AGENDA"]` — never a tool's free-text description. */
    val toolFamilies: List<String>,
    val systemPromptChars: Int,
    /**
     * § FASE 2A.6 §1 — the SPECIFIC families `RelevantToolSelector.matchedFamilies`
     * found (as opposed to [toolFamilies], which for the conservative
     * "ambiguous request" fallback is every family in the whole catalog).
     * `ConversationalJarvisEngine` intersects this with `GROUNDED_FAMILIES` to
     * compute the turn's real `requiredGroundingFamilies` — an ambiguous
     * fallback must never be misread as "every grounded family was
     * specifically requested."
     */
    val specificFamilies: List<String> = emptyList(),
)
