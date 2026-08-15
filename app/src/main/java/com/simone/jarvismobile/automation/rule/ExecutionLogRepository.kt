package com.simone.jarvismobile.automation.rule

import com.simone.jarvismobile.core.automation.rule.ExecutionDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** One line of history, ready to show ("Arrivo a casa · 22:14 · attivata perché…"). */
data class ExecutionRecord(
    val executionId: String,
    val ruleId: String,
    val ruleName: String,
    val triggerType: String,
    val at: LocalDateTime?,
    val decision: ExecutionDecision,
    val reason: String,
    val actions: List<Pair<String, String>>,
    val dryRun: Boolean,
) {
    val fired: Boolean get() = decision.fired
    val failedActions: List<String> get() = actions.filter { it.second != "ok" }.map { it.first }
}

/**
 * The "perché l'hai fatto?" history (§21).
 *
 * What is deliberately *not* stored is as important as what is: no coordinates,
 * no notification bodies, no message text — only which rule, which trigger, what
 * was decided and how each action ended. A log that quietly accumulated places
 * and message contents would be a tracking history the user never asked for, and
 * it would live on in every backup.
 *
 * Retention is bounded for the same reason.
 */
@Singleton
class ExecutionLogRepository @Inject constructor(
    private val dao: AutomationExecutionDao,
) {

    fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<ExecutionRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toRecord() } }

    suspend fun forRule(ruleId: String, limit: Int = 20): List<ExecutionRecord> =
        dao.forRule(ruleId, limit).map { it.toRecord() }

    suspend fun write(report: ExecutionReport) {
        dao.insert(
            AutomationExecutionEntity(
                executionId = report.executionId,
                ruleId = report.rule.id,
                ruleName = report.rule.name,
                triggerType = report.event.type,
                startedAt = report.event.at.toString(),
                finishedAt = LocalDateTime.now().toString(),
                decision = report.decision.name,
                reason = report.reason.take(MAX_REASON_CHARS),
                actionOutcomes = report.outcomeSummary().take(MAX_OUTCOME_CHARS),
                dryRun = report.dryRun,
            ),
        )
    }

    /** Drops entries older than [days]. Called from periodic maintenance. */
    suspend fun prune(days: Long = RETENTION_DAYS, now: LocalDateTime = LocalDateTime.now()): Int =
        dao.deleteOlderThan(now.minusDays(days).toString())

    suspend fun count(): Int = dao.count()

    private fun AutomationExecutionEntity.toRecord() = ExecutionRecord(
        executionId = executionId,
        ruleId = ruleId,
        ruleName = ruleName,
        triggerType = triggerType,
        at = runCatching { LocalDateTime.parse(startedAt) }.getOrNull(),
        decision = runCatching { ExecutionDecision.valueOf(decision) }
            .getOrDefault(ExecutionDecision.SKIP_NOT_RUNNABLE),
        reason = reason,
        actions = actionOutcomes.split(',')
            .filter { it.isNotBlank() }
            .map { pair ->
                val type = pair.substringBefore(':')
                val outcome = pair.substringAfter(':', "")
                type to outcome
            },
        dryRun = dryRun,
    )

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val RETENTION_DAYS = 30L
        const val MAX_REASON_CHARS = 200
        const val MAX_OUTCOME_CHARS = 400
    }
}
