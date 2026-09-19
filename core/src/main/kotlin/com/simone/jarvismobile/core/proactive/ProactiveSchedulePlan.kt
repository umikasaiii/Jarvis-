package com.simone.jarvismobile.core.proactive

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §5/§7/§15. The pure shape of a DURABLE DERIVED execution
 * plan for one scheduling source (NEXT_ALARM or CONFIGURED_TIME) — never a
 * second settings authority ([com.simone.jarvismobile.data.SettingsRepository]
 * remains the settings source of truth; this is what the scheduler derived
 * from it, plus the OS booking result, kept separate per §7). The Android
 * Room row this maps to lives in `app/`; this model exists so the
 * revision/date comparison a fired intent must pass (§15) is plain,
 * unit-testable logic.
 */
data class ProactiveSchedulePlan(
    val source: ProactiveTriggerSource,
    val planRevision: Long,
    val logicalDate: String,
    val intendedFireAtMs: Long,
    val sourceAlarmAtMs: Long?,
    val enabled: Boolean,
    val reconciliationOutcome: String,
    val exactness: ScheduleExactness,
    val updatedAtMs: Long,
)

/**
 * § §15 (RECEIVER VALIDATION). A fired intent carries the plan revision and
 * logical date it was scheduled under; a receiver must validate both against
 * the CURRENT plan before acting. No match → NO-OP, never "recompute with
 * current settings and deliver anyway" (that would make every stale intent
 * silently correct itself instead of surfacing the staleness).
 */
object StaleIntentValidator {
    fun isValid(
        intentPlanRevision: Long,
        intentLogicalDate: String,
        currentPlanRevision: Long?,
        currentLogicalDate: String?,
    ): Boolean =
        currentPlanRevision != null &&
            currentLogicalDate != null &&
            intentPlanRevision == currentPlanRevision &&
            intentLogicalDate == currentLogicalDate
}
