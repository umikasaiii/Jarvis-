package com.simone.jarvismobile.core.automation.rule

import java.time.Duration
import java.time.LocalDateTime

/**
 * Decides whether a rule may run — and records why not when it may not (§11, §21).
 *
 * All of the reasoning lives here, in pure code, because this is where the
 * expensive mistakes are: firing twice for one event, firing during a cooldown,
 * firing when a condition the user set could not be checked. The app layer keeps
 * only the parts that genuinely need Android — running the actions and reading
 * the sensors.
 */

/** What happened to a candidate firing. Exactly one applies. */
enum class ExecutionDecision {
    FIRE,
    SKIP_NOT_RUNNABLE,
    SKIP_DISABLED,
    SKIP_EXPIRED,
    SKIP_COOLDOWN,
    SKIP_CONDITIONS,
    SKIP_DUPLICATE,
    SKIP_CAPABILITY,
    SKIP_CONFLICT,
    ;

    val fired: Boolean get() = this == FIRE
}

/**
 * A trigger firing. [dedupKey] distinguishes genuinely different occurrences of
 * the same trigger type: for a place it is the place id, for a notification the
 * posting key. Two events with the same key inside the dedup window are the same
 * happening seen twice — which is exactly what §11 forbids acting on twice.
 */
data class TriggerEvent(
    val type: String,
    val at: LocalDateTime,
    val dedupKey: String = "",
) {
    /** Identity of this occurrence for one rule. */
    fun idempotencyKey(ruleId: String): String = "$ruleId|$type|$dedupKey"
}

/** What the engine remembers about a rule's recent past. */
data class ExecutionHistory(
    val lastFiredAt: LocalDateTime? = null,
    /** Idempotency key → when it was last acted on. */
    val recentKeys: Map<String, LocalDateTime> = emptyMap(),
    /** True while a previous run of this rule is still going. */
    val running: Boolean = false,
)

/** The gate's verdict, with enough detail for the "perché?" log. */
data class GateResult(
    val decision: ExecutionDecision,
    val reason: String,
    val unmet: List<Condition> = emptyList(),
) {
    val fired: Boolean get() = decision.fired
}

object RuleGate {

    /** Default window in which the same event seen twice counts as one. */
    const val DEFAULT_DEDUP_SECONDS = 30L

    /**
     * Decides whether [rule] should run for [event].
     *
     * [capabilityOf] answers whether the platform can actually deliver a given
     * ability; a rule whose actions are not all supported is skipped with a
     * reason rather than half-run, so JARVIS never reports doing something it
     * could not do.
     */
    @Suppress("ReturnCount")
    fun evaluate(
        rule: AutomationRule,
        event: TriggerEvent,
        context: EvaluationContext,
        history: ExecutionHistory = ExecutionHistory(),
        dedupSeconds: Long = DEFAULT_DEDUP_SECONDS,
        // Last so it can be passed as a trailing lambda, which is how callers
        // will nearly always supply it.
        capabilityOf: (Capability) -> CapabilityStatus = { CapabilityStatus.SUPPORTED },
    ): GateResult {
        if (!rule.enabled) return GateResult(ExecutionDecision.SKIP_DISABLED, "regola disattivata")
        if (rule.actions.isEmpty() || rule.triggers.isEmpty()) {
            return GateResult(ExecutionDecision.SKIP_NOT_RUNNABLE, "regola incompleta")
        }
        if (rule.hasExpired(event.at)) return GateResult(ExecutionDecision.SKIP_EXPIRED, "regola scaduta")

        // The rule must actually listen for this trigger — and for a place, the
        // *right* place, not merely a place-of-the-same-kind.
        if (!TriggerMatching.ruleListens(rule, event)) {
            return GateResult(ExecutionDecision.SKIP_NOT_RUNNABLE, "trigger non previsto da questa regola")
        }

        // Duplicate suppression comes before the cooldown: two deliveries of one
        // happening are not "two firings too close together", they are one.
        val key = event.idempotencyKey(rule.id)
        history.recentKeys[key]?.let { seenAt ->
            if (Duration.between(seenAt, event.at).seconds < dedupSeconds) {
                return GateResult(ExecutionDecision.SKIP_DUPLICATE, "evento già gestito")
            }
        }

        if (history.running && rule.executionPolicy == ExecutionPolicy.SKIP_IF_RUNNING) {
            return GateResult(ExecutionDecision.SKIP_DUPLICATE, "esecuzione già in corso")
        }

        val effective = rule.copy(lastFiredAt = history.lastFiredAt ?: rule.lastFiredAt)
        if (!effective.cooldownElapsed(event.at)) {
            return GateResult(ExecutionDecision.SKIP_COOLDOWN, "troppo presto dopo l'ultima volta")
        }

        // Capability last among the cheap checks, but before conditions, so a
        // rule that cannot run says so rather than blaming the context.
        val missing = missingCapabilities(rule, capabilityOf)
        if (missing.isNotEmpty()) {
            return GateResult(
                ExecutionDecision.SKIP_CAPABILITY,
                "manca un permesso o una funzione: ${missing.joinToString(", ") { it.name }}",
            )
        }

        val unmet = ConditionEvaluator.unmetLeaves(rule.condition, context)
        if (!ConditionEvaluator.evaluate(rule.condition, context)) {
            return GateResult(ExecutionDecision.SKIP_CONDITIONS, "condizioni non soddisfatte", unmet)
        }

        return GateResult(ExecutionDecision.FIRE, "trigger ${event.type}")
    }

    /** Capabilities this rule needs that the platform cannot currently give. */
    fun missingCapabilities(
        rule: AutomationRule,
        capabilityOf: (Capability) -> CapabilityStatus,
    ): List<Capability> {
        val needed = buildSet {
            rule.triggers.mapNotNull { TriggerRegistry.definition(it.type)?.requires }.forEach(::add)
            rule.actions.mapNotNull { ActionRegistry.definition(it.type)?.requires }.forEach(::add)
        } - Capability.NONE
        return needed.filterNot { capabilityOf(it).usable }
    }
}

/**
 * Settles the case where several rules want the same exclusive state at once
 * (§12): one rule switching to silent while another switches to ringing.
 *
 * Without this the two would take turns and the phone would flap. The rule with
 * the highest [AutomationRule.priority] wins; ties are broken by id so the
 * outcome is stable rather than depending on database order — a conflict that
 * resolved differently each time would be impossible to explain to the user.
 */
object ConflictResolver {

    data class Loser(val rule: AutomationRule, val resource: String, val winnerId: String)

    data class Resolution(
        val winners: List<AutomationRule>,
        val losers: List<Loser>,
    )

    fun resolve(candidates: List<AutomationRule>): Resolution {
        if (candidates.size <= 1) return Resolution(candidates, emptyList())

        val claimed = mutableMapOf<String, AutomationRule>()
        val losers = mutableListOf<Loser>()
        // Strongest first, so the first claimant of a resource is its owner.
        val ordered = candidates.sortedWith(
            compareByDescending<AutomationRule> { it.priority }.thenBy { it.id },
        )
        val winners = mutableListOf<AutomationRule>()

        for (rule in ordered) {
            val resources = ActionRegistry.exclusiveResources(rule.actions)
            val conflict = resources.firstOrNull { it in claimed }
            if (conflict != null) {
                losers += Loser(rule, conflict, claimed.getValue(conflict).id)
                continue
            }
            resources.forEach { claimed[it] = rule }
            winners += rule
        }
        return Resolution(winners, losers)
    }
}
