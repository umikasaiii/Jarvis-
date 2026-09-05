package com.simone.jarvismobile.core.semantic

import com.simone.jarvismobile.core.tools.ToolFamily

/**
 * § FASE 2A.9 §4 — the model's own output format: ONE line, a fixed schema
 * marker, closed-vocabulary enum tokens, `-` for "not established by this
 * turn's own words" (never guessed/blank-means-something-else). Modeled
 * directly on `LlmIntentClassifier`'s existing `name|confidence` precedent
 * (§ FASE 2A.9 §11 "prior art") — a tiny model reproduces a short, rigid,
 * closed-vocabulary line far more reliably than well-formed JSON, and this
 * parser never trusts anything it cannot validate against a known enum.
 *
 * Field order: `SFV1|intent|domains|operation|temporal|metric|aggregation|reference|confidence`
 * - `domains`: comma-separated [ToolFamily] names, or `-` for none.
 * - `temporal`/`metric`/`aggregation`: free text preserved verbatim (never
 *   parsed here — see [SemanticFrame]'s own doc comment), or `-`.
 * - A field's raw token being `-` is exactly what marks that
 *   [SemanticSlot] as NOT explicit — see [SemanticFrameMerger].
 */
object SemanticOutputParser {

    const val SCHEMA_MARKER = "SFV1"
    private const val FIELD_COUNT = 9
    private const val EMPTY_TOKEN = "-"

    /**
     * Strict parse: wrong field count, wrong schema marker, an unrecognized
     * intent/operation/reference token, or an unparsable confidence all
     * produce [SemanticInterpretation.Invalid] — never a partially-trusted
     * frame, never a crash. A raw domain token that doesn't match any
     * [ToolFamily] is silently dropped (not fatal on its own) — if that
     * leaves zero domains where the model claimed at least one, the frame
     * is still returned (domains empty, not explicit) rather than rejected
     * outright: an interpreter that named a domain wrong is still useful for
     * its intent/operation, and the router treats an empty-domain frame as
     * ambiguous on its own already.
     */
    fun parse(raw: String): SemanticInterpretation {
        val line = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return SemanticInterpretation.Invalid("empty_output")
        val parts = line.split("|").map { it.trim() }
        if (parts.size != FIELD_COUNT) return SemanticInterpretation.Invalid("field_count:${parts.size}")
        if (parts[0] != SCHEMA_MARKER) return SemanticInterpretation.Invalid("schema_marker:${parts[0]}")

        val intent = enumOrNull<SemanticIntent>(parts[1]) ?: return SemanticInterpretation.Invalid("intent:${parts[1]}")
        val operation = enumOrNull<SemanticOperation>(parts[3]) ?: return SemanticInterpretation.Invalid("operation:${parts[3]}")
        val reference = enumOrNull<ReferenceMode>(parts[7]) ?: return SemanticInterpretation.Invalid("reference:${parts[7]}")
        val confidence = parts[8].toDoubleOrNull()?.coerceIn(0.0, 1.0)
            ?: return SemanticInterpretation.Invalid("confidence:${parts[8]}")

        val domainsRaw = parts[2]
        val domains = if (domainsRaw == EMPTY_TOKEN || domainsRaw.isBlank()) {
            emptySet()
        } else {
            domainsRaw.split(",").mapNotNull { token -> enumOrNull<ToolFamily>(token.trim()) }.toSet()
        }

        val temporal = parts[4].takeIf { it != EMPTY_TOKEN && it.isNotBlank() }
        val metric = parts[5].takeIf { it != EMPTY_TOKEN && it.isNotBlank() }
        val aggregation = parts[6].takeIf { it != EMPTY_TOKEN && it.isNotBlank() }

        val explicit = buildSet {
            if (domainsRaw != EMPTY_TOKEN && domainsRaw.isNotBlank()) add(SemanticSlot.DOMAINS)
            // § the operation field has no `-` token of its own (it must
            // always be a valid enum name) — SemanticOperation.UNKNOWN IS
            // its "not established by this turn" value.
            if (operation != SemanticOperation.UNKNOWN) add(SemanticSlot.OPERATION)
            if (temporal != null) add(SemanticSlot.TEMPORAL_EXPRESSION)
            if (metric != null) add(SemanticSlot.METRIC)
            if (aggregation != null) add(SemanticSlot.AGGREGATION)
        }

        val frame = SemanticFrame(
            intent = intent,
            domains = domains,
            operation = operation,
            temporalExpression = temporal,
            metric = metric,
            aggregation = aggregation,
            entities = emptyList(),
            referenceMode = reference,
            requiresGrounding = domains.isNotEmpty() && intent == SemanticIntent.CAPABILITY_QUERY,
            confidence = confidence,
            explicitSlots = explicit,
        )
        return SemanticFrameValidator.validate(frame)
    }

    private inline fun <reified E : Enum<E>> enumOrNull(token: String): E? =
        enumValues<E>().firstOrNull { it.name.equals(token, ignoreCase = true) }
}
