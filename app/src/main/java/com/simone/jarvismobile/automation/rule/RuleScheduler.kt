package com.simone.jarvismobile.automation.rule

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.alarms.ExactAlarms
import com.simone.jarvismobile.core.automation.rule.RuleSchedule
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Books exact alarms for the generic engine's clock triggers, and keeps them in
 * step with the rules (phase 5 — "the wiring that makes the engine live").
 *
 * This is the new-engine sibling of the old [com.simone.jarvismobile.automation.AutomationScheduler].
 * They share the [ExactAlarms] plumbing but use different alarm kinds, so the two
 * engines never fight over the same pending intents and the 6f automations keep
 * working untouched until hand-over.
 *
 * Only the *next* occurrence of each rule is ever booked: a rule that never runs
 * cannot pile up a backlog of missed firings, and a daily rule books its
 * successor from the moment it actually fired. Place, Bluetooth and the other
 * event triggers are not booked here — they arrive from their own sources.
 */
@Singleton
class RuleScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rules: RuleRepository,
    private val alarms: ExactAlarms,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Re-points the schedule at exactly the enabled clock rules, nothing else. */
    suspend fun sync(now: LocalDateTime = LocalDateTime.now()) {
        val candidates = runCatching { rules.all() }.getOrElse {
            Log.w(TAG, "rule_sync_read_failed ${it.javaClass.simpleName}")
            return
        }.filter { it.enabled && !it.hasExpired(now) && RuleSchedule.hasScheduledTrigger(it) }

        val desired = LinkedHashMap<String, Pair<String, LocalDateTime>>()
        for (rule in candidates) {
            val next = RuleSchedule.nextForRule(rule, now) ?: continue
            desired[workName(rule.id)] = next.first.type to next.second
        }

        val known = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        (known - desired.keys).forEach(alarms::cancel)
        desired.forEach { (name, plan) ->
            val ruleId = name.removePrefix(PREFIX)
            enqueueAt(name, ruleId, plan.first, plan.second)
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, desired.keys.toSet()).apply()
        Log.i(TAG, "rule_schedule synced=${desired.size}")
    }

    fun cancel(id: String) {
        val name = workName(id)
        alarms.cancel(name)
        val known = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        prefs.edit().putStringSet(KEY_SCHEDULED, known - name).apply()
    }

    /** The single point where a due time becomes a platform wake-up. */
    private fun enqueueAt(key: String, ruleId: String, triggerType: String, at: LocalDateTime) {
        alarms.schedule(
            key = key,
            at = at,
            extras = mapOf(
                ExactAlarms.EXTRA_KIND to ExactAlarms.KIND_RULE,
                ExactAlarms.EXTRA_ID to ruleId,
                ExactAlarms.EXTRA_TRIGGER_TYPE to triggerType,
                // The occurrence is the dedup key: two deliveries of the same 08:00
                // are one event, tomorrow's 08:00 is a different one.
                ExactAlarms.EXTRA_OCCURRENCE to at.toString(),
            ),
        )
    }

    private fun workName(id: String) = "$PREFIX$id"

    companion object {
        private const val PREFIX = "jarvis_rule_"
        private const val PREFS = "jarvis_rule_schedule"
        private const val KEY_SCHEDULED = "scheduled_rules"
    }
}
