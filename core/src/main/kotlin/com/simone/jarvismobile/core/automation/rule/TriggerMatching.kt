package com.simone.jarvismobile.core.automation.rule

/**
 * Whether a rule's trigger is the one that just fired (§8).
 *
 * Type alone is not enough for targeted triggers: a rule armed on "arrivo a
 * casa" must not fire when the phone enters the office. For place triggers the
 * [TriggerSpec]'s `placeId` has to match the place the event carries (its
 * [TriggerEvent.dedupKey]); a place trigger with no `placeId` means "any place",
 * which is what a rule like "quando arrivo da qualche parte" would want.
 *
 * Kept here, pure and tested, so the gate and the executor decide "is this my
 * event?" the same way — a mismatch between the two would either drop firings or
 * fire the wrong rule.
 */
object TriggerMatching {

    private val PLACE_TYPES = setOf(
        TriggerRegistry.PLACE_ENTER,
        TriggerRegistry.PLACE_EXIT,
        TriggerRegistry.PLACE_DWELL,
    )

    /** Whether [spec] is a trigger for [event]. */
    fun matches(spec: TriggerSpec, event: TriggerEvent): Boolean {
        if (spec.type != event.type) return false
        return when (event.type) {
            in PLACE_TYPES -> {
                val want = spec.param("placeId") ?: return true
                want == event.dedupKey
            }
            else -> true
        }
    }

    /** Whether any of [rule]'s triggers is the one that fired. */
    fun ruleListens(rule: AutomationRule, event: TriggerEvent): Boolean =
        rule.triggers.any { matches(it, event) }
}
