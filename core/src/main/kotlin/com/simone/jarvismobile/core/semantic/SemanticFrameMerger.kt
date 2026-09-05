package com.simone.jarvismobile.core.semantic

/**
 * The outcome of one merge — [inheritedSlots] and [currentOverridesPrevious]
 * are exactly the diagnostics fields § FASE 2A.9 §15 asks for
 * (`explicitSlots`/`inheritedSlots`/`currentOverridesPrevious`), so a caller
 * never has to recompute them separately from [frame].
 */
data class SemanticMergeResult(
    val frame: SemanticFrame,
    val inheritedSlots: Set<SemanticSlot>,
    val currentOverridesPrevious: Boolean,
)

/**
 * § FASE 2A.9 — the permanent architectural rule, as code:
 * `SIGNIFICATO ESPLICITO DEL TURNO ATTUALE > CONTESTO PRECEDENTE > DEFAULT`.
 *
 * A slot in [SemanticFrame.explicitSlots] was established by THIS turn's own
 * words — it is returned completely untouched, no matter what [previous]
 * says. A slot NOT in that set may be filled in from [previous], but ONLY
 * when domain continuity holds: either the current turn re-asserted a
 * domain [previous] also had, or (for a domain-less, otherwise-eligible
 * follow-up) the domain itself was inherited in this same call. Filling in
 * an unrelated slot (say, [SemanticFrame.metric]) from a [previous] frame
 * whose DOMAIN was explicitly overridden this turn would silently smuggle
 * old context back in through a side door — exactly the class of bug this
 * whole phase exists to close, so it never happens here.
 *
 * Canonical example this pins: previous turn HEALTH ("Quanto ho dormito?"),
 * current turn "Domani farà caldo?" (WEATHER, explicit) — HEALTH's
 * `domains` never reaches the merged frame, because
 * [SemanticSlot.DOMAINS] was explicit this turn.
 */
object SemanticFrameMerger {

    /** [SemanticIntent]s a current, domain-less frame may inherit a domain FOR. */
    private val DOMAIN_INHERITING_INTENTS = setOf(SemanticIntent.CAPABILITY_QUERY)

    /** [SemanticIntent]s a [previous] frame may be a domain SOURCE for. */
    private val DOMAIN_SOURCE_INTENTS = setOf(SemanticIntent.CAPABILITY_QUERY, SemanticIntent.MULTI_SOURCE_REASONING)

    fun merge(current: SemanticFrame, previous: SemanticFrame?): SemanticMergeResult {
        if (previous == null) return SemanticMergeResult(current, emptySet(), currentOverridesPrevious = false)

        val currentExplicitDomain = SemanticSlot.DOMAINS in current.explicitSlots && current.domains.isNotEmpty()
        val eligibleForDomainInherit = !currentExplicitDomain &&
            current.domains.isEmpty() &&
            current.intent in DOMAIN_INHERITING_INTENTS &&
            previous.intent in DOMAIN_SOURCE_INTENTS &&
            previous.domains.isNotEmpty()

        val inherited = mutableSetOf<SemanticSlot>()
        val domains = if (eligibleForDomainInherit) {
            inherited += SemanticSlot.DOMAINS
            previous.domains
        } else {
            current.domains
        }

        // Domain continuity: either this turn explicitly named a domain
        // [previous] also touched, or the domain itself was just inherited
        // above. Only under continuity do the OTHER slots make sense to
        // pull from [previous] at all.
        val continuity = eligibleForDomainInherit ||
            (currentExplicitDomain && domains.any { it in previous.domains })

        val operation = if (SemanticSlot.OPERATION in current.explicitSlots) {
            current.operation
        } else if (continuity) {
            inherited += SemanticSlot.OPERATION
            previous.operation
        } else {
            current.operation
        }

        fun inheritText(slot: SemanticSlot, currentValue: String?, previousValue: String?): String? {
            if (slot in current.explicitSlots) return currentValue
            if (!continuity || previousValue == null) return currentValue
            inherited += slot
            return previousValue
        }

        val temporal = inheritText(SemanticSlot.TEMPORAL_EXPRESSION, current.temporalExpression, previous.temporalExpression)
        val metric = inheritText(SemanticSlot.METRIC, current.metric, previous.metric)
        val aggregation = inheritText(SemanticSlot.AGGREGATION, current.aggregation, previous.aggregation)
        val entities = if (current.entities.isNotEmpty() || !continuity) current.entities else previous.entities
        if (entities !== current.entities && entities.isNotEmpty()) inherited += SemanticSlot.ENTITIES

        val merged = current.copy(
            domains = domains,
            operation = operation,
            temporalExpression = temporal,
            metric = metric,
            aggregation = aggregation,
            entities = entities,
        )
        return SemanticMergeResult(merged, inherited, currentOverridesPrevious = currentExplicitDomain)
    }
}
