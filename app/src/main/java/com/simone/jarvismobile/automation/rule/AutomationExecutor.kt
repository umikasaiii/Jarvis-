package com.simone.jarvismobile.automation.rule

import android.util.Log
import com.simone.jarvismobile.core.automation.rule.ActionRegistry
import com.simone.jarvismobile.core.automation.rule.AutomationRule
import com.simone.jarvismobile.core.automation.rule.ConflictResolver
import com.simone.jarvismobile.core.automation.rule.EvaluationContext
import com.simone.jarvismobile.core.automation.rule.ExecutionDecision
import com.simone.jarvismobile.core.automation.rule.ExecutionHistory
import com.simone.jarvismobile.core.automation.rule.GateResult
import com.simone.jarvismobile.core.automation.rule.RuleGate
import com.simone.jarvismobile.core.automation.rule.TriggerEvent
import com.simone.jarvismobile.core.automation.rule.TriggerMatching
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the rules a trigger woke up (§11).
 *
 * Responsibilities, in order: ask the gate whether each rule may run, settle
 * conflicts between the survivors, execute their actions with a timeout, and
 * write down what happened and why. Nothing here decides policy — that all lives
 * in the pure `:core` gate — so this class stays about Android concerns:
 * concurrency, timeouts and persistence.
 *
 * Two guarantees matter most:
 *  - **an event is acted on once.** The in-memory record of recent idempotency
 *    keys, plus the running-rule set, keeps a duplicated delivery from producing
 *    duplicated side effects;
 *  - **nothing is reported as done unless it was.** A partial failure is logged
 *    as a partial failure, never rounded up to success.
 */
@Singleton
class AutomationExecutor @Inject constructor(
    private val rules: RuleRepository,
    private val handlers: ActionHandlerRegistry,
    private val capabilities: CapabilityManager,
    private val log: ExecutionLogRepository,
) {

    /** Idempotency key → when it was last acted on. Bounded, see [rememberKey]. */
    private val recentKeys = ConcurrentHashMap<String, LocalDateTime>()

    /** Rules with a run in flight, so SKIP_IF_RUNNING can mean something. */
    private val running = ConcurrentHashMap.newKeySet<String>()

    private val fireMutex = Mutex()

    /**
     * Handles one trigger firing end to end.
     *
     * [dryRun] evaluates and logs exactly as usual but performs no side effects,
     * which is what the diagnostics screen's "test automation" uses (§22).
     */
    suspend fun onTrigger(
        event: TriggerEvent,
        context: EvaluationContext,
        dryRun: Boolean = false,
    ): List<ExecutionReport> {
        val candidates = runCatching { rules.armable(event.at) }.getOrElse {
            Log.w(TAG, "rules_unreadable ${it.javaClass.simpleName}")
            return emptyList()
        }.filter { rule -> TriggerMatching.ruleListens(rule, event) }
        if (candidates.isEmpty()) return emptyList()

        // Gate first: only rules that may actually run get to compete for the
        // exclusive states, otherwise a blocked rule could still veto another.
        val gated = candidates.map { it to gate(it, event, context) }
        val allowed = gated.filter { it.second.fired }.map { it.first }
        val resolution = ConflictResolver.resolve(allowed)

        val reports = mutableListOf<ExecutionReport>()

        // Everything the gate refused is written down with its reason, so the
        // log can answer "why didn't it?" as well as "why did it?".
        for ((rule, result) in gated.filterNot { it.second.fired }) {
            reports += record(rule, event, result, emptyList(), dryRun)
        }
        for (loser in resolution.losers) {
            reports += record(
                loser.rule, event,
                GateResult(
                    ExecutionDecision.SKIP_CONFLICT,
                    "«${loser.resource}» già assegnato a un'automazione con priorità maggiore",
                ),
                emptyList(), dryRun,
            )
        }

        for (rule in resolution.winners) {
            reports += execute(rule, event, dryRun)
        }
        return reports
    }

    private fun gate(rule: AutomationRule, event: TriggerEvent, context: EvaluationContext): GateResult =
        RuleGate.evaluate(
            rule = rule,
            event = event,
            context = context,
            history = ExecutionHistory(
                lastFiredAt = rule.lastFiredAt,
                recentKeys = recentKeys.toMap(),
                running = rule.id in running,
            ),
            capabilityOf = capabilities.asLookup(),
        )

    private suspend fun execute(
        rule: AutomationRule,
        event: TriggerEvent,
        dryRun: Boolean,
    ): ExecutionReport {
        // Claim the key and the running flag together, so two deliveries racing
        // on different threads cannot both get past the gate.
        val key = event.idempotencyKey(rule.id)
        val claimed = fireMutex.withLock {
            if (rule.id in running) {
                false
            } else {
                running += rule.id
                rememberKey(key, event.at)
                true
            }
        }
        if (!claimed) {
            return record(
                rule, event,
                GateResult(ExecutionDecision.SKIP_DUPLICATE, "esecuzione già in corso"),
                emptyList(), dryRun,
            )
        }

        val outcomes = mutableListOf<Pair<String, ActionOutcome>>()
        try {
            for (spec in rule.actions) {
                val handler = handlers.handler(spec.type)
                if (handler == null) {
                    // Declared in the registry but nothing implements it: say so
                    // rather than counting it as done.
                    outcomes += spec.type to ActionOutcome.Failed("nessun gestore per ${spec.type}")
                    continue
                }
                val outcome = try {
                    withTimeout(ACTION_TIMEOUT_MS) { handler.handle(spec, dryRun) }
                } catch (e: TimeoutCancellationException) {
                    ActionOutcome.Failed("timeout")
                } catch (e: Exception) {
                    // One misbehaving action must not take JARVIS down (§25).
                    Log.w(TAG, "action_crash ${spec.type} ${e.javaClass.simpleName}")
                    ActionOutcome.Failed(e.javaClass.simpleName)
                }
                outcomes += spec.type to outcome
            }
        } finally {
            running -= rule.id
        }

        val anyDone = outcomes.any { it.second is ActionOutcome.Done }
        if (anyDone && !dryRun) {
            runCatching { rules.markFired(rule.id, event.at) }
            // A one-shot rule has now spent itself.
            if (rule.oneShot) runCatching { rules.setEnabled(rule.id, false, event.at) }
        }

        // The rule did fire, whatever the actions made of it; the truth about
        // what actually happened lives in the per-action outcomes, which is why
        // ExecutionReport exposes succeeded/partial separately from the decision.
        return record(
            rule, event,
            GateResult(ExecutionDecision.FIRE, if (dryRun) "prova (dry-run)" else "trigger ${event.type}"),
            outcomes, dryRun,
        )
    }

    /**
     * Keeps the recent-key map from growing without bound. Old entries can be
     * dropped safely: past the dedup window they no longer suppress anything.
     */
    private fun rememberKey(key: String, at: LocalDateTime) {
        recentKeys[key] = at
        if (recentKeys.size > MAX_REMEMBERED_KEYS) {
            val cutoff = at.minusSeconds(RuleGate.DEFAULT_DEDUP_SECONDS * 2)
            recentKeys.entries.removeIf { it.value.isBefore(cutoff) }
        }
    }

    private suspend fun record(
        rule: AutomationRule,
        event: TriggerEvent,
        result: GateResult,
        outcomes: List<Pair<String, ActionOutcome>>,
        dryRun: Boolean,
    ): ExecutionReport {
        val report = ExecutionReport(
            executionId = UUID.randomUUID().toString(),
            rule = rule,
            event = event,
            decision = result.decision,
            reason = buildReason(result),
            actions = outcomes,
            dryRun = dryRun,
        )
        runCatching { log.write(report) }
            .onFailure { Log.w(TAG, "execution_log_failed ${it.javaClass.simpleName}") }
        Log.i(TAG, "automation ${rule.id} ${result.decision} actions=${outcomes.size} dry=$dryRun")
        return report
    }

    /** Adds the failed conditions to the reason, so the log names them. */
    private fun buildReason(result: GateResult): String {
        if (result.unmet.isEmpty()) return result.reason
        val names = result.unmet.joinToString(", ") { describe(it) }
        return "${result.reason}: $names"
    }

    private fun describe(condition: com.simone.jarvismobile.core.automation.rule.Condition): String =
        condition::class.simpleName ?: "condizione"

    private companion object {
        const val TAG = "JarvisAutomation"

        /** No single action may hold the engine: §25 "action timeout". */
        const val ACTION_TIMEOUT_MS = 15_000L
        const val MAX_REMEMBERED_KEYS = 200
    }
}

/** What one rule did (or did not do) for one event. */
data class ExecutionReport(
    val executionId: String,
    val rule: AutomationRule,
    val event: TriggerEvent,
    val decision: ExecutionDecision,
    val reason: String,
    val actions: List<Pair<String, ActionOutcome>>,
    val dryRun: Boolean,
) {
    val succeeded: Boolean
        get() = decision.fired && actions.isNotEmpty() &&
            actions.all { it.second is ActionOutcome.Done }

    /** True when some actions worked and others did not (§11 partial failure). */
    val partial: Boolean
        get() = decision.fired &&
            actions.any { it.second is ActionOutcome.Done } &&
            actions.any { it.second !is ActionOutcome.Done }

    /** Compact, privacy-safe summary: action types and outcomes only. */
    fun outcomeSummary(): String =
        actions.joinToString(",") { "${it.first}:${it.second.label}" }

    /** The risk this rule's actions amount to, for the UI. */
    fun risk() = ActionRegistry.riskOf(rule.actions)
}
