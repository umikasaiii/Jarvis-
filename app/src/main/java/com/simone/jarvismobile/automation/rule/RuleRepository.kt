package com.simone.jarvismobile.automation.rule

import android.util.Log
import com.simone.jarvismobile.core.automation.rule.AutomationRule
import com.simone.jarvismobile.core.automation.rule.LegacyRuleConverter
import com.simone.jarvismobile.core.automation.rule.RuleCodec
import com.simone.jarvismobile.core.automation.rule.validationErrors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** A rule row that could not be decoded, kept so the user can repair it. */
data class QuarantinedRule(val id: String, val name: String)

/**
 * The single way in and out of automation storage.
 *
 * Two behaviours here are deliberate and worth stating, because both concern
 * losing user data:
 *
 * 1. **A rule that cannot be decoded is quarantined, never dropped and never
 *    partially loaded.** Loading a rule whose condition failed to parse would
 *    widen it — a rule meant to run only at home would start running everywhere.
 *    Deleting it would throw away something the user wrote. So the row stays,
 *    flagged, and is simply never armed.
 * 2. **Saving validates first.** A rule armed on a trigger this build cannot
 *    deliver is refused with a reason rather than stored looking active.
 */
@Singleton
class RuleRepository @Inject constructor(
    private val dao: AutomationRuleDao,
    private val settings: com.simone.jarvismobile.data.SettingsRepository,
) {

    /** Rules that decoded cleanly, newest schema first. */
    fun observeRules(): Flow<List<AutomationRule>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    /** Rows we could not read, for the diagnostics screen. */
    fun observeQuarantined(): Flow<List<QuarantinedRule>> =
        dao.observeAll().map { rows ->
            rows.filter { it.quarantined || it.toDomainOrNull() == null }
                .map { QuarantinedRule(it.id, it.name) }
        }

    suspend fun all(): List<AutomationRule> = dao.all().mapNotNull { it.toDomainOrNull() }

    /**
     * Rules that listen for [triggerType] and could be decoded — **without**
     * filtering on cooldown or expiry.
     *
     * Those checks belong to the gate, not here. Dropping an on-cooldown rule at
     * this level would keep it out of the execution log entirely, so the history
     * could never answer "perché non è scattata?" for the single most common
     * reason; it also left the gate's SKIP_COOLDOWN/SKIP_EXPIRED branches
     * unreachable.
     */
    suspend fun candidatesFor(triggerType: String): List<AutomationRule> =
        dao.enabled().mapNotNull { it.toDomainOrNull() }
            .filter { rule -> rule.triggers.any { it.type == triggerType } }


    suspend fun byId(id: String): AutomationRule? = dao.byId(id)?.toDomainOrNull()

    /**
     * Stores [rule] after validating it. Returns the problems found; an empty
     * list means it was saved.
     */
    suspend fun save(rule: AutomationRule, now: LocalDateTime = LocalDateTime.now()): List<String> {
        val errors = rule.validationErrors()
        if (errors.isNotEmpty()) {
            Log.w(TAG, "rule_rejected ${rule.id} problems=${errors.size}")
            return errors
        }
        val stamped = rule.copy(
            createdAt = rule.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(stamped.toEntity())
        Log.i(TAG, "rule_saved ${stamped.logLine()}")
        return emptyList()
    }

    suspend fun setEnabled(id: String, enabled: Boolean, now: LocalDateTime = LocalDateTime.now()) {
        dao.setEnabled(id, enabled, now.toString())
    }

    suspend fun markFired(id: String, now: LocalDateTime = LocalDateTime.now()) {
        dao.markFired(id, now.toString())
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun count(): Int = dao.count()

    /**
     * One-time import of rules written by the previous, Markdown-based engine.
     *
     * Only shapes the new model can express exactly are carried over. A legacy
     * rule with no equivalent here is **left where it is** and reported, rather
     * than approximated — an automation that fires at a different time than the
     * user set is worse than one that visibly did not migrate.
     *
     * Returns the ids of legacy rules that were imported.
     */
    suspend fun importLegacy(
        legacy: List<com.simone.jarvismobile.core.automation.Automation>,
        now: LocalDateTime = LocalDateTime.now(),
    ): ImportReport {
        // A row count cannot answer "has this already run?": deleting every
        // imported rule would drop it to zero and resurrect rules the user
        // deliberately threw away. The answer has to be remembered explicitly.
        if (settings.legacyAutomationsImported.first()) return ImportReport(alreadyDone = true)
        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (old in legacy) {
            val converted = LegacyRuleConverter.convert(old, now)
            if (converted == null) {
                // Say *why*, so the user can recreate the rule by hand instead of
                // discovering months later that it quietly stopped existing.
                skipped += "${old.name} — ${LegacyRuleConverter.reasonForSkipping(old).orEmpty()}"
                continue
            }
            val problems = converted.validationErrors()
            if (problems.isNotEmpty()) {
                skipped += "${old.name} — ${problems.first()}"
                continue
            }
            dao.upsert(converted.toEntity())
            imported += old.name
        }
        settings.markLegacyAutomationsImported()
        Log.i(TAG, "legacy_import done=${imported.size} skipped=${skipped.size}")
        return ImportReport(imported = imported, skipped = skipped)
    }

    data class ImportReport(
        val imported: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val alreadyDone: Boolean = false,
    )

    // --- Mapping -------------------------------------------------------

    private fun AutomationRuleEntity.toDomainOrNull(): AutomationRule? {
        if (quarantined) return null
        val decoded = RuleCodec.decodeRule(
            id = id,
            name = name,
            enabled = enabled,
            triggersJson = triggersJson,
            conditionJson = conditionJson,
            actionsJson = actionsJson,
            priority = priority,
            executionPolicy = executionPolicy,
            cooldownSeconds = cooldownSeconds,
            oneShot = oneShot,
            expiresAt = expiresAt,
            lastFiredAt = lastFiredAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            schemaVersion = schemaVersion,
        )
        if (decoded == null) Log.w(TAG, "rule_undecodable $id schema=$schemaVersion")
        return decoded
    }

    private fun AutomationRule.toEntity() = AutomationRuleEntity(
        id = id,
        name = name,
        enabled = enabled,
        triggersJson = RuleCodec.encodeTriggers(triggers),
        conditionJson = condition?.let { RuleCodec.encodeCondition(it) },
        actionsJson = RuleCodec.encodeActions(actions),
        priority = priority,
        executionPolicy = executionPolicy.name,
        cooldownSeconds = cooldownSeconds,
        oneShot = oneShot,
        expiresAt = expiresAt?.toString(),
        lastFiredAt = lastFiredAt?.toString(),
        createdAt = createdAt?.toString(),
        updatedAt = updatedAt?.toString(),
        schemaVersion = schemaVersion,
        quarantined = false,
    )

    private companion object {
        const val TAG = "JarvisAutomation"
    }
}
